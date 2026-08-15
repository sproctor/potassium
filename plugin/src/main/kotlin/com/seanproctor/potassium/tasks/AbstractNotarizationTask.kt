/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.tasks

import com.seanproctor.potassium.dsl.MacOSNotarizationSettings
import com.seanproctor.potassium.dsl.TargetFormat
import com.seanproctor.potassium.internal.NOTARIZATION_REQUEST_INFO_FILE_NAME
import com.seanproctor.potassium.internal.NotarizationRequestInfo
import com.seanproctor.potassium.internal.NotarizationStatus
import com.seanproctor.potassium.internal.files.checkExistingFile
import com.seanproctor.potassium.internal.files.findOutputFileOrDir
import com.seanproctor.potassium.internal.utils.MacUtils
import com.seanproctor.potassium.internal.utils.ioFile
import com.seanproctor.potassium.internal.validation.ValidatedMacOSNotarizationSettings
import com.seanproctor.potassium.internal.validation.toNotaryToolArgs
import com.seanproctor.potassium.internal.validation.toNotaryToolCredentialValues
import com.seanproctor.potassium.internal.validation.validate
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject

@DisableCachingByDefault(because = "Depends on external Apple notarization service")
abstract class AbstractNotarizationTask
    @Inject
    constructor(
        @get:Input
        val targetFormat: TargetFormat,
    ) : AbstractPotassiumTask() {
        @get:Nested
        @get:Optional
        internal var nonValidatedNotarizationSettings: MacOSNotarizationSettings? = null

        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        val inputDir: DirectoryProperty = objects.directoryProperty()

        init {
            check(targetFormat != TargetFormat.JpackageImage) { "${TargetFormat.JpackageImage} cannot be notarized!" }
        }

        @TaskAction
        fun run() {
            val notarization = nonValidatedNotarizationSettings.validate()
            val packageFile = findOutputFileOrDir(inputDir.ioFile, targetFormat).checkExistingFile()

            notarize(notarization, packageFile)
            val stapled = staple(packageFile)
            updateMetadataFiles(packageFile)
            if (stapled && deleteStaleBlockMap(packageFile)) {
                logger.lifecycle("Deleted stale ${packageFile.name}.blockmap (invalidated by stapling)")
            }
            // This submission is finished with. Drop the record so that an unrelated failure of a
            // later run cannot resume against a submission that has already been stapled.
            notarizationRequestInfoFile.delete()
        }

        private fun notarize(
            notarization: ValidatedMacOSNotarizationSettings,
            packageFile: File,
        ) {
            val artifactSha512 = sha512Base64(packageFile)
            if (resumePreviousSubmission(notarization, packageFile, artifactSha512)) return
            submitForNotarization(notarization, packageFile, artifactSha512)
        }

        private fun submitForNotarization(
            notarization: ValidatedMacOSNotarizationSettings,
            packageFile: File,
            artifactSha512: String,
        ) {
            logger.lifecycle("Uploading '${packageFile.name}' for notarization")
            val (authArgs, stdin) = notarization.auth.toNotaryToolArgs()
            val args =
                buildList {
                    add("notarytool")
                    add("submit")
                    add("--wait")
                    addAll(authArgs)
                    add(packageFile.absolutePath)
                }

            var submissionId: String? = null
            var stdout = ""
            var stderr = ""

            val result =
                runExternalTool(
                    tool = MacUtils.xcrun,
                    args = args,
                    stdinStr = stdin,
                    checkExitCodeIsNormal = false,
                    processStdout = { output ->
                        stdout = output
                        submissionId = SUBMISSION_ID_REGEX.find(output)?.groupValues?.get(1)
                    },
                    processStderr = { stderr = it },
                )

            if (submissionId != null) {
                logger.lifecycle("Notarization submission ID: $submissionId (file: ${packageFile.name})")
                saveNotarizationRequestInfo(checkNotNull(submissionId), artifactSha512)
            }

            val status = NotarizationStatus.parse(stdout)
            if (result.exitValue != 0 || (status != null && status != NotarizationStatus.Accepted)) {
                failNotarization(
                    notarization = notarization,
                    packageFile = packageFile,
                    submissionId = submissionId,
                    status = status,
                    exitValue = result.exitValue,
                    // notarytool's own stdout/stderr is the only diagnostic when the submission never
                    // reaches Apple (e.g. an authentication failure produces no submission ID or log).
                    // The app-specific password is fed via stdin, so it never appears in this output.
                    toolOutput = listOf(stdout, stderr).joinToString(separator = "\n").trim(),
                )
            }
        }

        /**
         * Picks up the submission recorded by an earlier run of this task that was interrupted
         * after the upload. Polling Apple for a verdict is the least reliable step of the whole
         * pipeline, and starting over abandons a submission that is often about to be accepted.
         *
         * Returns true when that submission has been accepted, so the caller can go straight to
         * stapling, and false when there is nothing usable to resume and the artifact has to be
         * uploaded. Fails the build when Apple rejected the submission: these are the same bytes,
         * so uploading them again would only earn the same verdict.
         */
        private fun resumePreviousSubmission(
            notarization: ValidatedMacOSNotarizationSettings,
            packageFile: File,
            artifactSha512: String,
        ): Boolean {
            val submissionId = previousSubmissionId(packageFile, artifactSha512) ?: return false

            logger.lifecycle("Found notarization submission $submissionId for the current '${packageFile.name}'")
            val info =
                runNotaryToolQuery(notarization, subcommand = "info", submissionId = submissionId)
                    ?: run {
                        logger.lifecycle("Could not read the status of submission $submissionId; uploading again")
                        return false
                    }

            val status = NotarizationStatus.parse(info)
            return when (status) {
                NotarizationStatus.Accepted -> {
                    logger.lifecycle("Submission $submissionId was already accepted; skipping the upload")
                    true
                }
                NotarizationStatus.InProgress -> awaitPreviousSubmission(notarization, packageFile, submissionId)
                NotarizationStatus.Invalid, NotarizationStatus.Rejected ->
                    failNotarization(
                        notarization = notarization,
                        packageFile = packageFile,
                        submissionId = submissionId,
                        status = status,
                        exitValue = null,
                        toolOutput = info.trim(),
                    )
                null -> {
                    logger.lifecycle("Submission $submissionId reports no status this plugin knows; uploading again")
                    false
                }
            }
        }

        /**
         * Waits out a submission Apple is still processing. Returns true once it is accepted, and
         * fails the build otherwise — including when the wait itself dies, since the submission is
         * still live and re-uploading the artifact would throw it away for nothing.
         */
        private fun awaitPreviousSubmission(
            notarization: ValidatedMacOSNotarizationSettings,
            packageFile: File,
            submissionId: String,
        ): Boolean {
            logger.lifecycle("Submission $submissionId is still in progress; waiting for it instead of uploading again")
            val output =
                runNotaryToolQuery(notarization, subcommand = "wait", submissionId = submissionId)
                    ?: error(
                        buildString {
                            appendLine("Could not wait for notarization submission $submissionId")
                            appendLine(
                                "The submission is still live on Apple's side, so '${packageFile.name}' was not " +
                                    "uploaded again. Re-run this task to keep waiting for it.",
                            )
                            append("To force a fresh submission, delete ${notarizationRequestInfoFile.absolutePath}")
                        },
                    )

            val status = NotarizationStatus.parse(output)
            if (status != NotarizationStatus.Accepted) {
                failNotarization(
                    notarization = notarization,
                    packageFile = packageFile,
                    submissionId = submissionId,
                    status = status,
                    exitValue = null,
                    toolOutput = output.trim(),
                )
            }
            logger.lifecycle("Submission $submissionId was accepted")
            return true
        }

        /**
         * The submission id recorded by an earlier run, but only when it describes the very bytes
         * about to be notarized. A record left by a previous build is ignored: its verdict says
         * nothing about the artifact sitting there now.
         */
        private fun previousSubmissionId(
            packageFile: File,
            artifactSha512: String,
        ): String? {
            val propsFile = notarizationRequestInfoFile
            val info =
                try {
                    NotarizationRequestInfo.loadFrom(propsFile)
                } catch (e: IOException) {
                    logger.warn("Ignoring notarization record ${propsFile.absolutePath}: ${e.message}")
                    null
                }
            if (info == null) return null

            if (info.artifactSha512 != artifactSha512) {
                logger.info(
                    "Ignoring notarization record ${propsFile.absolutePath}: " +
                        "it belongs to a different build of '${packageFile.name}'",
                )
                return null
            }
            return info.uuid
        }

        /**
         * Runs a read-only `notarytool` subcommand against an existing submission. Returns its
         * output, or null when the command failed — an unknown or expired submission id, an
         * authentication problem, or a connection that dropped mid-poll.
         */
        private fun runNotaryToolQuery(
            notarization: ValidatedMacOSNotarizationSettings,
            subcommand: String,
            submissionId: String,
        ): String? {
            val (authArgs, stdin) = notarization.auth.toNotaryToolArgs()
            var stdout = ""
            var stderr = ""

            val result =
                runExternalTool(
                    tool = MacUtils.xcrun,
                    args =
                        buildList {
                            add("notarytool")
                            add(subcommand)
                            add(submissionId)
                            addAll(authArgs)
                        },
                    stdinStr = stdin,
                    checkExitCodeIsNormal = false,
                    processStdout = { stdout = it },
                    processStderr = { stderr = it },
                )

            if (result.exitValue != 0) {
                logger.warn("'notarytool $subcommand $submissionId' failed with exit code ${result.exitValue}")
                val toolOutput = listOf(stdout, stderr).joinToString(separator = "\n").trim()
                if (toolOutput.isNotEmpty()) {
                    logger.warn(toolOutput)
                }
                return null
            }
            return stdout
        }

        private fun failNotarization(
            notarization: ValidatedMacOSNotarizationSettings,
            packageFile: File,
            submissionId: String?,
            status: NotarizationStatus?,
            exitValue: Int?,
            toolOutput: String,
        ): Nothing {
            val appleLog = fetchNotarizationLog(notarization, submissionId)
            val errMsg =
                buildString {
                    appendLine("Notarization failed for '${packageFile.name}'")
                    if (submissionId != null) {
                        appendLine("Submission ID: $submissionId")
                    }
                    if (status != null) {
                        appendLine("Status: ${status.printedName}")
                    }
                    if (exitValue != null && exitValue != 0) {
                        appendLine("Exit code: $exitValue")
                    }
                    if (toolOutput.isNotEmpty()) {
                        appendLine("notarytool output:")
                        appendLine(toolOutput)
                    }
                    if (appleLog != null) {
                        appendLine("Apple notarization log:")
                        appendLine(appleLog)
                    } else if (submissionId != null) {
                        // Spelled out rather than filled in: the auth arguments carry the Apple ID,
                        // team, keychain profile and API key identifiers, and build logs are kept.
                        appendLine("To fetch the log manually run:")
                        appendLine("  xcrun notarytool log $submissionId <the credentials this build notarizes with>")
                    }
                    if (submissionId != null && (status == null || status == NotarizationStatus.InProgress)) {
                        // Apple never reached a verdict, so the upload is not wasted: the saved
                        // record means the next run picks this submission up instead of re-uploading.
                        appendLine("Re-running this task resumes submission $submissionId instead of uploading again.")
                    }
                }
            error(errMsg)
        }

        private val notarizationRequestInfoFile: File
            get() = temporaryDir.resolve(NOTARIZATION_REQUEST_INFO_FILE_NAME)

        private fun saveNotarizationRequestInfo(
            submissionId: String,
            artifactSha512: String,
        ) {
            val info = NotarizationRequestInfo(uuid = submissionId, artifactSha512 = artifactSha512)
            val propsFile = notarizationRequestInfoFile
            info.saveTo(propsFile)
            logger.info("Saved notarization request info to ${propsFile.absolutePath}")
        }

        /**
         * Attempts to fetch the notarization log from Apple.
         * Returns the log content on success, or null if it cannot be retrieved.
         */
        private fun fetchNotarizationLog(
            notarization: ValidatedMacOSNotarizationSettings,
            submissionId: String?,
        ): String? {
            if (submissionId == null) return null

            val (authArgs, stdin) = notarization.auth.toNotaryToolArgs()
            return try {
                var logContent = ""
                runExternalTool(
                    tool = MacUtils.xcrun,
                    args =
                        buildList {
                            add("notarytool")
                            add("log")
                            add(submissionId)
                            addAll(authArgs)
                        },
                    stdinStr = stdin,
                    processStdout = { logContent = it },
                    // This is the one notarytool call that reports a failure by throwing the
                    // command line back at us, and that message is logged below.
                    sensitiveArgs = notarization.auth.toNotaryToolCredentialValues(),
                )
                logContent.ifEmpty { null }
            } catch (e: IllegalStateException) {
                logger.warn("Could not fetch notarization log: ${e.message}")
                null
            }
        }

        /** Returns true when the file was stapled (and therefore rewritten). */
        private fun staple(packageFile: File): Boolean {
            if (packageFile.extension.equals("zip", ignoreCase = true)) {
                // ZIP files used for auto-update are not stapled: re-zipping after stapling
                // would invalidate the blockmap and break differential updates.
                // Notarization is still verified online by Gatekeeper without stapling.
                logger.lifecycle("Skipping staple for ${packageFile.name} (ZIP auto-update artifact)")
                return false
            }
            runExternalTool(
                tool = MacUtils.xcrun,
                args = listOf("stapler", "staple", packageFile.absolutePath),
            )
            return true
        }

        private fun updateMetadataFiles(packageFile: File) {
            val dir = packageFile.parentFile ?: return
            val fileName = packageFile.name
            val newSize = packageFile.length()
            val newHash = sha512Base64(packageFile)

            val ymlFiles = dir.listFiles { f -> f.extension == "yml" || f.extension == "yaml" } ?: return
            for (ymlFile in ymlFiles) {
                val content = ymlFile.readText()
                if (!content.contains(fileName)) continue

                val updated = updateYamlEntry(content, fileName, newHash, newSize)
                if (updated != content) {
                    ymlFile.writeText(updated)
                    logger.lifecycle("Updated checksums in ${ymlFile.name} for $fileName")
                }
            }
        }

        private fun sha512Base64(file: File): String {
            val digest = MessageDigest.getInstance("SHA-512")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = input.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return Base64.getEncoder().encodeToString(digest.digest())
        }

        companion object {
            private const val DEFAULT_BUFFER_SIZE = 8192
            private val SUBMISSION_ID_REGEX = Regex("""^\s*id:\s*([0-9a-fA-F-]+)\s*$""", RegexOption.MULTILINE)

            /**
             * Stapling rewrites the package, so a `.blockmap` sidecar generated from the
             * pre-staple bytes no longer matches the published artifact. Nothing consumes
             * dmg/pkg blockmaps for auto-update (macOS updates use the ZIP, which is never
             * stapled), so the stale sidecar is dropped instead of being published.
             * Returns true when a sidecar existed and was deleted.
             */
            internal fun deleteStaleBlockMap(packageFile: File): Boolean {
                val blockMapFile = File(packageFile.parentFile, "${packageFile.name}.blockmap")
                return blockMapFile.isFile && blockMapFile.delete()
            }

            internal fun updateYamlEntry(
                yaml: String,
                fileName: String,
                newHash: String,
                newSize: Long,
            ): String {
                val lines = yaml.lines().toMutableList()
                var i = 0
                var topLevelPath: String? = null

                while (i < lines.size) {
                    val line = lines[i]
                    val trimmed = line.trimStart()

                    if (isUrlEntry(trimmed) && extractUrl(trimmed) == fileName) {
                        i = updateFileEntryFields(lines, i + 1, newHash, newSize)
                        continue
                    }

                    val isTopLevel = !line.startsWith(" ") && !line.startsWith("\t")
                    if (isTopLevel && trimmed.startsWith("path:")) {
                        topLevelPath = trimmed.removePrefix("path:").trim()
                    }
                    if (isTopLevel && trimmed.startsWith("sha512:") && topLevelPath == fileName) {
                        lines[i] = "sha512: $newHash"
                    }

                    i++
                }

                return lines.joinToString("\n")
            }

            private fun isUrlEntry(trimmed: String): Boolean =
                trimmed.startsWith("- url:") || trimmed.startsWith("-url:")

            private fun extractUrl(trimmed: String): String =
                trimmed
                    .removePrefix("-")
                    .trimStart()
                    .removePrefix("url:")
                    .trim()

            private fun isEndOfFileEntry(entryLine: String): Boolean {
                if (isUrlEntry(entryLine)) return true
                if (entryLine.startsWith("blockMapSize:")) return false
                return !entryLine.startsWith(" ") && entryLine.contains(":")
            }

            private fun updateFileEntryFields(
                lines: MutableList<String>,
                startIndex: Int,
                newHash: String,
                newSize: Long,
            ): Int {
                var i = startIndex
                while (i < lines.size) {
                    val entryLine = lines[i].trimStart()
                    if (entryLine.startsWith("sha512:")) {
                        val indent = lines[i].length - lines[i].trimStart().length
                        lines[i] = " ".repeat(indent) + "sha512: $newHash"
                    } else if (entryLine.startsWith("size:")) {
                        val indent = lines[i].length - lines[i].trimStart().length
                        lines[i] = " ".repeat(indent) + "size: $newSize"
                    } else if (isEndOfFileEntry(entryLine)) {
                        break
                    }
                    i++
                }
                return i
            }
        }
    }
