/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.tasks

import com.seanproctor.potassium.internal.ExternalToolRunner
import com.seanproctor.potassium.internal.JvmRuntimeProperties
import com.seanproctor.potassium.internal.PotassiumProperties
import com.seanproctor.potassium.internal.utils.*
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import java.io.File

internal const val MIN_JAVA_RUNTIME_VERSION = 17

/** Keys read from the JDK's `release` file (standard since JDK 9). */
private const val RELEASE_JAVA_VERSION_KEY = "JAVA_VERSION"
private const val RELEASE_IMPLEMENTOR_KEY = "IMPLEMENTOR"

@CacheableTask
abstract class AbstractCheckNativeDistributionRuntime : AbstractPotassiumTask() {
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    @get:InputDirectory
    val jdkHome: Property<String> = objects.notNullProperty()

    @get:Input
    abstract val checkJdkVendor: Property<Boolean>

    private val taskDir = project.layout.buildDirectory.dir("potassium/tmp/$name")

    @get:OutputFile
    val javaRuntimePropertiesFile: Provider<RegularFile> = taskDir.map { it.file("properties.bin") }

    private val jdkHomeFile: File
        get() = File(jdkHome.orNull ?: error("Missing jdkHome value"))

    private fun File.getJdkTool(toolName: String): File = resolve("bin/${executableName(toolName)}")

    private fun ensureToolsExist(vararg tools: File) {
        val missingTools = tools.filter { !it.exists() }.map { "'${it.name}'" }

        if (missingTools.isEmpty()) return

        if (missingTools.size == 1) jdkDistributionProbingError("${missingTools.single()} is missing")

        jdkDistributionProbingError("${missingTools.joinToString(", ")} are missing")
    }

    private fun jdkDistributionProbingError(errorMessage: String): Nothing {
        val fullErrorMessage =
            buildString {
                appendLine("Failed to check JDK distribution: $errorMessage")
                appendLine("JDK distribution path: ${jdkHomeFile.absolutePath}")
            }
        error(fullErrorMessage)
    }

    @TaskAction
    fun run() {
        taskDir.ioFile.mkdirs()

        val jdkHome = jdkHomeFile
        val javaExecutable = jdkHome.getJdkTool("java")
        val jlinkExecutable = jdkHome.getJdkTool("jlink")
        val jpackageExecutabke = jdkHome.getJdkTool("jpackage")
        ensureToolsExist(javaExecutable, jlinkExecutable, jpackageExecutabke)

        val releaseProperties = readReleaseFile(jdkHome)

        val javaVersionString =
            releaseProperties[RELEASE_JAVA_VERSION_KEY]
                ?: jdkDistributionProbingError("Could not read '$RELEASE_JAVA_VERSION_KEY' from $jdkHome/release")
        val jdkMajorVersion =
            javaVersionString.substringBefore('.').toIntOrNull()
                ?: jdkDistributionProbingError("JDK version '$javaVersionString' has unexpected format")

        check(jdkMajorVersion >= MIN_JAVA_RUNTIME_VERSION) {
            jdkDistributionProbingError(
                "minimum required JDK version is '$MIN_JAVA_RUNTIME_VERSION', " +
                    "but actual version is '$jdkMajorVersion'",
            )
        }

        if (checkJdkVendor.get()) {
            val vendor = releaseProperties[RELEASE_IMPLEMENTOR_KEY]
            if (vendor == null) {
                logger.warn("JDK vendor probe failed: $jdkHome")
            } else {
                if (currentOS == OS.MacOS && vendor.equals("homebrew", ignoreCase = true)) {
                    error(
                        """
                            |Homebrew's JDK distribution may cause issues with packaging.
                            |See: https://github.com/JetBrains/compose-multiplatform/issues/3107
                            |Possible solutions:
                            |* Use other vendor's JDK distribution, such as Amazon Corretto;
                            |* To continue using Homebrew distribution for packaging on your own risk, add "${PotassiumProperties.CHECK_JDK_VENDOR}=false" to your gradle.properties
                        """.trimMargin(),
                    )
                }
            }
        }

        val modules = arrayListOf<String>()
        runExternalTool(
            tool = javaExecutable,
            args = listOf("--list-modules"),
            logToConsole = ExternalToolRunner.LogToConsole.Never,
            processStdout = { stdout ->
                stdout.lineSequence().forEach { line ->
                    val moduleName = line.trim().substringBefore("@")
                    if (moduleName.isNotBlank()) {
                        modules.add(moduleName)
                    }
                }
            },
        )

        val properties = JvmRuntimeProperties(jdkMajorVersion, modules)
        JvmRuntimeProperties.writeToFile(properties, javaRuntimePropertiesFile.ioFile)
    }

    /** Parses the target JDK's `release` file (`KEY="value"` per line) without executing it. */
    private fun readReleaseFile(jdkHome: File): Map<String, String> {
        val releaseFile = File(jdkHome, "release")
        if (!releaseFile.exists()) {
            jdkDistributionProbingError("No 'release' file found at ${releaseFile.absolutePath}")
        }
        return releaseFile.readLines().mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim().trim('"')
        }.toMap()
    }
}
