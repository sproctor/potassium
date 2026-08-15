/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium

import com.seanproctor.potassium.dsl.AotCacheSettings
import com.seanproctor.potassium.dsl.CompressionLevel
import com.seanproctor.potassium.dsl.GraalvmSettings
import com.seanproctor.potassium.dsl.JvmApplication
import com.seanproctor.potassium.dsl.JvmApplicationBuildTypes
import com.seanproctor.potassium.dsl.JvmMacOSPlatformSettings
import com.seanproctor.potassium.dsl.LinuxPlatformSettings
import com.seanproctor.potassium.dsl.NativeApplication
import com.seanproctor.potassium.dsl.PublishSettings
import com.seanproctor.potassium.dsl.UrlProtocol
import com.seanproctor.potassium.dsl.WindowsPlatformSettings
import com.seanproctor.potassium.internal.JvmApplicationInternal
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import java.io.File
import javax.inject.Inject

/**
 * The `potassium { }` extension — the whole plugin DSL.
 *
 * It *is* the JVM desktop application **and** its packaging configuration: set `mainClass`, the
 * package metadata (`packageName`, `targetFormats(...)`, `macOS { }`, `publish { }`, …),
 * `buildTypes { }`, `graalvm { }`, etc. directly here. (There is no nested `application { }` or
 * `nativeDistributions { }` block.)
 *
 * Members forward to a lazily-created [JvmApplicationInternal] and its packaging config; touching
 * any of them marks the JVM application as configured (see [isJvmApplicationInitialized]), which is
 * how the plugin distinguishes a JVM app from a [nativeApplication]-only project.
 */
@Suppress("TooManyFunctions", "LargeClass")
abstract class PotassiumExtension
    @Inject
    constructor(
        private val objectFactory: ObjectFactory,
    ) : JvmApplication(),
        ExtensionAware {
        internal var isJvmApplicationInitialized = false
            private set

        internal val jvmApplication: JvmApplicationInternal by lazy {
            isJvmApplicationInitialized = true
            objectFactory.newInstance(JvmApplicationInternal::class.java, "main")
        }

        private val distributions get() = jvmApplication.nativeDistributions

        // --- Application / launch configuration ---

        override fun from(from: SourceSet) = jvmApplication.from(from)

        override fun from(from: KotlinTarget) = jvmApplication.from(from)

        override fun disableDefaultConfiguration() = jvmApplication.disableDefaultConfiguration()

        override fun dependsOn(vararg tasks: Task) = jvmApplication.dependsOn(*tasks)

        override fun dependsOn(vararg tasks: String) = jvmApplication.dependsOn(*tasks)

        override fun fromFiles(vararg files: Any) = jvmApplication.fromFiles(*files)

        override var mainClass: String?
            get() = jvmApplication.mainClass
            set(value) {
                jvmApplication.mainClass = value
            }

        override val mainJar: RegularFileProperty get() = jvmApplication.mainJar

        override var javaHome: String
            get() = jvmApplication.javaHome
            set(value) {
                jvmApplication.javaHome = value
            }

        override val args: MutableList<String> get() = jvmApplication.args

        override fun args(vararg args: String) = jvmApplication.args(*args)

        override val jvmArgs: MutableList<String> get() = jvmApplication.jvmArgs

        override fun jvmArgs(vararg jvmArgs: String) = jvmApplication.jvmArgs(*jvmArgs)

        override val buildTypes: JvmApplicationBuildTypes get() = jvmApplication.buildTypes

        override fun buildTypes(fn: Action<JvmApplicationBuildTypes>) = jvmApplication.buildTypes(fn)

        override val graalvm: GraalvmSettings get() = jvmApplication.graalvm

        override fun graalvm(fn: Action<GraalvmSettings>) = jvmApplication.graalvm(fn)

        // --- Package metadata & native distribution (formerly the nativeDistributions { } block) ---

        var appName: String?
            get() = distributions.appName
            set(value) {
                distributions.appName = value
            }

        var packageName: String?
            get() = distributions.packageName
            set(value) {
                distributions.packageName = value
            }

        var packageVersion: String?
            get() = distributions.packageVersion
            set(value) {
                distributions.packageVersion = value
            }

        var copyright: String?
            get() = distributions.copyright
            set(value) {
                distributions.copyright = value
            }

        var description: String?
            get() = distributions.description
            set(value) {
                distributions.description = value
            }

        var vendor: String?
            get() = distributions.vendor
            set(value) {
                distributions.vendor = value
            }

        var homepage: String?
            get() = distributions.homepage
            set(value) {
                distributions.homepage = value
            }

        val outputBaseDir: DirectoryProperty get() = distributions.outputBaseDir

        val appResourcesRootDir: DirectoryProperty get() = distributions.appResourcesRootDir

        val licenseFile: RegularFileProperty get() = distributions.licenseFile

        // targetFormats are configured per-OS inside the macOS { } / windows { } / linux { } blocks.

        // Mutable property with a real getter/setter delegating to distributions; cannot be `val`.
        @Suppress("DoubleMutabilityForCollection")
        var modules: ArrayList<String>
            get() = distributions.modules
            set(value) {
                distributions.modules = value
            }

        fun modules(vararg modules: String) = distributions.modules(*modules)

        var includeAllModules: Boolean
            get() = distributions.includeAllModules
            set(value) {
                distributions.includeAllModules = value
            }

        var cleanupNativeLibs: Boolean
            get() = distributions.cleanupNativeLibs
            set(value) {
                distributions.cleanupNativeLibs = value
            }

        var splashImage: String?
            get() = distributions.splashImage
            set(value) {
                distributions.splashImage = value
            }

        var enableAotCache: Boolean
            get() = distributions.enableAotCache
            set(value) {
                distributions.enableAotCache = value
            }

        val aotCache: AotCacheSettings get() = distributions.aotCache

        fun aotCache(fn: Action<AotCacheSettings>) = distributions.aotCache(fn)

        var compressionLevel: CompressionLevel?
            get() = distributions.compressionLevel
            set(value) {
                distributions.compressionLevel = value
            }

        var artifactName: String
            get() = distributions.artifactName
            set(value) {
                distributions.artifactName = value
            }

        val trustedCertificates: ConfigurableFileCollection get() = distributions.trustedCertificates

        val protocols: MutableList<UrlProtocol> get() = distributions.protocols

        fun protocol(
            name: String,
            vararg schemes: String,
        ) = distributions.protocol(name, *schemes)

        val linux: LinuxPlatformSettings get() = distributions.linux

        fun linux(fn: Action<LinuxPlatformSettings>) = distributions.linux(fn)

        val macOS: JvmMacOSPlatformSettings get() = distributions.macOS

        fun macOS(fn: Action<JvmMacOSPlatformSettings>) = distributions.macOS(fn)

        val windows: WindowsPlatformSettings get() = distributions.windows

        fun windows(fn: Action<WindowsPlatformSettings>) = distributions.windows(fn)

        val publish: PublishSettings get() = distributions.publish

        fun publish(fn: Action<PublishSettings>) = distributions.publish(fn)

        @JvmOverloads
        fun fileAssociation(
            mimeType: String,
            extension: String,
            description: String,
            linuxIconFile: File? = null,
            windowsIconFile: File? = null,
            macOSIconFile: File? = null,
        ) = distributions.fileAssociation(
            mimeType,
            extension,
            description,
            linuxIconFile,
            windowsIconFile,
            macOSIconFile,
        )

        // --- Kotlin/Native (macOS) application: a separate, nested block ---

        internal var isNativeApplicationInitialized = false
            private set

        val nativeApplication: NativeApplication by lazy {
            isNativeApplicationInitialized = true
            objectFactory.newInstance(NativeApplication::class.java, "main")
        }

        fun nativeApplication(fn: Action<NativeApplication>) {
            fn.execute(nativeApplication)
        }
    }
