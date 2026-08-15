/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.tasks

import com.seanproctor.potassium.internal.ExternalToolRunner
import com.seanproctor.potassium.internal.files.mangledName
import com.seanproctor.potassium.internal.files.normalizedPath
import com.seanproctor.potassium.internal.utils.clearDirs
import com.seanproctor.potassium.internal.utils.ioFile
import com.seanproctor.potassium.internal.utils.jvmToolFile
import com.seanproctor.potassium.internal.utils.notNullProperty
import com.seanproctor.potassium.internal.utils.nullableProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.Writer

@DisableCachingByDefault(because = "Depends on external ProGuard tool")
abstract class AbstractProguardTask : AbstractPotassiumTask() {
    @get:InputFiles
    @get:Classpath
    val inputFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val mainJar: RegularFileProperty = objects.fileProperty()

    @get:Internal
    val mainJarBaseName: Property<String> = objects.property(String::class.java)

    @get:Internal
    internal val mainJarInDestinationDir: Provider<RegularFile>
        get() = destinationDir.file(mainJarBaseName)

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val configurationFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:Optional
    @get:Input
    val dontobfuscate: Property<Boolean> = objects.nullableProperty()

    @get:Optional
    @get:Input
    val dontoptimize: Property<Boolean> = objects.nullableProperty()

    @get:Optional
    @get:Input
    val joinOutputJars: Property<Boolean> = objects.nullableProperty()

    // todo: DSL for excluding default rules
    // also consider pulling coroutines rules from coroutines artifact
    // https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/jvm/resources/META-INF/proguard/coroutines.pro
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val defaultProguardRulesFile: RegularFileProperty = objects.fileProperty()

    @get:Input
    val proguardVersion: Property<String> = objects.notNullProperty()

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    val proguardFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:Input
    val javaHome: Property<String> = objects.notNullProperty(System.getProperty("java.home"))

    @get:Input
    val mainClass: Property<String> = objects.notNullProperty()

    @get:Internal
    val maxHeapSize: Property<String> = objects.nullableProperty()

    @get:OutputDirectory
    val destinationDir: DirectoryProperty = objects.directoryProperty()

    @get:LocalState
    protected val workingDir: Provider<Directory> = project.layout.buildDirectory.dir("potassium/tmp/$name")

    private val rootConfigurationFile = workingDir.map { it.file("root-config.pro") }

    private val jarsConfigurationFile = workingDir.map { it.file("jars-config.pro") }

    @TaskAction
    fun execute() {
        val javaHome = File(javaHome.get())

        fileOperations.clearDirs(destinationDir, workingDir)
        val destinationDir = destinationDir.ioFile.absoluteFile

        // todo: can be cached for a jdk
        val libraryJarsConfig = jdkLibraryJarsConfig(javaHome)

        val inputToOutputJars = LinkedHashMap<File, File>()
        // avoid mangling mainJar
        inputToOutputJars[mainJar.ioFile] = mainJarInDestinationDir.ioFile
        for (inputFile in inputFiles) {
            if (inputFile.name.endsWith(".jar", ignoreCase = true)) {
                inputToOutputJars.putIfAbsent(inputFile, destinationDir.resolve(inputFile.mangledName()))
            } else {
                inputFile.copyTo(destinationDir.resolve(inputFile.name))
            }
        }

        jarsConfigurationFile.ioFile.bufferedWriter().use { writer ->
            val toSingleOutputJar = joinOutputJars.orNull == true
            for ((input, output) in inputToOutputJars.entries) {
                writer.writeLn("-injars '${input.normalizedPath()}'")
                if (!toSingleOutputJar) {
                    writer.writeLn("-outjars '${output.normalizedPath()}'")
                }
            }
            if (toSingleOutputJar) {
                writer.writeLn("-outjars '${mainJarInDestinationDir.ioFile.normalizedPath()}'")
            }

            for (libraryJar in libraryJarsConfig) {
                writer.writeLn(libraryJar)
            }
        }

        rootConfigurationFile.ioFile.bufferedWriter().use { writer ->
            if (dontobfuscate.orNull == true) {
                writer.writeLn("-dontobfuscate")
            }

            if (dontoptimize.orNull == true) {
                writer.writeLn("-dontoptimize")
            }

            writer.writeLn(
                """
                -keep public class ${mainClass.get()} {
                    public static void main(java.lang.String[]);
                }
                """.trimIndent(),
            )

            val includeFiles =
                sequenceOf(
                    jarsConfigurationFile.ioFile,
                    defaultProguardRulesFile.ioFile,
                ) + configurationFiles.files.asSequence()
            for (configFile in includeFiles) {
                writer.writeLn("-include '${configFile.normalizedPath()}'")
            }
        }

        val javaBinary = jvmToolFile(toolName = "java", javaHome = javaHome)
        // Unlike jlink/jpackage, these args are passed straight to java via ExecOperations.exec (no
        // @argfile), so each element reaches the process verbatim. They must NOT be quoted: the cliArg
        // helper quotes values for the argfile tokenizer, and a quoted -cp entry would be treated as a
        // literal (non-existent) path — silently dropping the first and last classpath entries.
        val args =
            arrayListOf<String>().apply {
                val maxHeapSize = maxHeapSize.orNull
                if (maxHeapSize != null) {
                    add("-Xmx:$maxHeapSize")
                }
                add("-cp")
                add(proguardFiles.joinToString(File.pathSeparator) { it.normalizedPath() })
                add("proguard.ProGuard")
                // todo: consider separate flag
                if (verbose.get()) add("-verbose")
                add("-include")
                add(rootConfigurationFile.get().asFile.normalizedPath())
            }

        runExternalTool(
            tool = javaBinary,
            args = args,
            environment = emptyMap(),
            logToConsole = ExternalToolRunner.LogToConsole.Always,
        ).assertNormalExitValue()
    }

    /**
     * `-libraryjars` lines covering the JDK's own classes.
     *
     * Modular JDKs ship them as jmod files under `jmods`, but distributions built with run-time image
     * linking (JEP 493, JDK 25+) drop that directory entirely — Temurin 25 is one of them. With no
     * library jars ProGuard cannot resolve even `java.lang.Object` and aborts after several hundred
     * thousand "can't find referenced class" warnings, so fall back to extracting the run-time image
     * with `jimage` and hand ProGuard one class root per module.
     */
    private fun jdkLibraryJarsConfig(javaHome: File): List<String> {
        val jmodsDir = javaHome.resolve("jmods")
        val jmods =
            jmodsDir
                .walk()
                .filter {
                    it.isFile && it.path.endsWith("jmod", ignoreCase = true)
                }.toList()
        if (jmods.isNotEmpty()) {
            return jmods.map { "-libraryjars '${it.normalizedPath()}'(!**.jar;!module-info.class)" }
        }

        val runtimeImage = javaHome.resolve("lib").resolve("modules")
        check(runtimeImage.isFile) {
            "Cannot resolve the JDK classes required by ProGuard: neither '$jmodsDir' nor " +
                "'$runtimeImage' exists. Point the application's javaHome at a JDK."
        }

        val modulesDir = workingDir.ioFile.resolve("jdk-modules")
        runExternalTool(
            tool = jvmToolFile(toolName = "jimage", javaHome = javaHome),
            args = listOf("extract", "--dir", modulesDir.normalizedPath(), runtimeImage.normalizedPath()),
        ).assertNormalExitValue()

        val moduleDirs = modulesDir.listFiles().orEmpty().filter { it.isDirectory }
        check(moduleDirs.isNotEmpty()) {
            "Extracting '$runtimeImage' produced no modules in '$modulesDir'."
        }
        return moduleDirs.map { "-libraryjars '${it.normalizedPath()}'(!module-info.class,**.class)" }
    }

    private fun Writer.writeLn(s: String) {
        write(s)
        write("\n")
    }
}
