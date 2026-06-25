/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:Suppress("unused")

package com.seanproctor.potassium

import groovy.lang.Closure
import com.seanproctor.potassium.desktop.application.internal.configureDesktop
import com.seanproctor.potassium.experimental.internal.configureExperimentalTargetsFlagsCheck
import com.seanproctor.potassium.internal.KOTLIN_MPP_PLUGIN_ID
import com.seanproctor.potassium.internal.mppExt
import com.seanproctor.potassium.internal.utils.currentTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

internal val composeVersion get() = PotassiumBuildConfig.composeVersion
internal val composeMaterial3Version get() = PotassiumBuildConfig.composeMaterial3Version

@Suppress("AbstractClassCanBeConcreteClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class PotassiumPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val potassiumExtension = project.extensions.create("potassium", PotassiumExtension::class.java, project)

        if ((project.dependencies as? ExtensionAware)?.extensions?.findByName("potassium") == null) {
            project.dependencies.extensions.add("potassium", Dependencies(project))
        }

        if (!project.buildFile.endsWith(".gradle.kts")) {
            setUpGroovyDslExtensions(project)
        }

        project.afterEvaluate {
            configureDesktop(project, potassiumExtension)
            project.plugins.withId(KOTLIN_MPP_PLUGIN_ID) {
                val mppExt = project.mppExt
                project.configureExperimentalTargetsFlagsCheck(mppExt)
            }
        }
    }

    @Suppress("DEPRECATION")
    class Dependencies(
        project: Project,
    ) {
        val desktop = DesktopDependencies

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.animation:animation:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val animation get() = composeDependency("org.jetbrains.compose.animation:animation")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.animation:animation-graphics:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val animationGraphics get() = composeDependency("org.jetbrains.compose.animation:animation-graphics")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.foundation:foundation:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val foundation get() = composeDependency("org.jetbrains.compose.foundation:foundation")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.material:material:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val material get() = composeDependency("org.jetbrains.compose.material:material")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.material3:material3:\${PotassiumBuildConfig.composeMaterial3Version}\"",
                ),
        )
        val material3 get() = composeMaterial3Dependency("org.jetbrains.compose.material3:material3")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.material3:material3-adaptive-navigation-suite:" +
                        "\${PotassiumBuildConfig.composeMaterial3Version}\"",
                ),
        )
        val material3AdaptiveNavigationSuite get() =
            composeMaterial3Dependency(
                "org.jetbrains.compose.material3:material3-adaptive-navigation-suite",
            )

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.runtime:runtime:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val runtime get() = composeDependency("org.jetbrains.compose.runtime:runtime")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.runtime:runtime-saveable:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val runtimeSaveable get() = composeDependency("org.jetbrains.compose.runtime:runtime-saveable")

        @Deprecated(
            "Specify dependency directly",
            replaceWith = ReplaceWith("\"org.jetbrains.compose.ui:ui:\${PotassiumBuildConfig.composeVersion}\""),
        )
        val ui get() = composeDependency("org.jetbrains.compose.ui:ui")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-test:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        @ExperimentalPotassiumLibrary
        val uiTest get() = composeDependency("org.jetbrains.compose.ui:ui-test")

        @Deprecated(
            "Use org.jetbrains.compose.ui:ui-tooling module instead",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-tooling:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val uiTooling get() = composeDependency("org.jetbrains.compose.ui:ui-tooling")

        @Deprecated(
            "Specify dependency directly",
            replaceWith = ReplaceWith("\"org.jetbrains.compose.ui:ui-util:\${PotassiumBuildConfig.composeVersion}\""),
        )
        val uiUtil get() = composeDependency("org.jetbrains.compose.ui:ui-util")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-tooling-preview:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val preview get() = composeDependency("org.jetbrains.compose.ui:ui-tooling-preview")

        @Deprecated(
            "This artifact is pinned to version 1.7.3 and will not receive updates. " +
                "Either use this version explicitly or migrate to Material Symbols (vector resources). " +
                "See https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html",
            replaceWith = ReplaceWith("\"org.jetbrains.compose.material:material-icons-extended:1.7.3\""),
        )
        val materialIconsExtended get() = "org.jetbrains.compose.material:material-icons-extended:1.7.3"

        @Deprecated("Specify dependency directly")
        val components get() = CommonComponentsDependencies
    }

    @Deprecated("Specify dependency directly")
    object DesktopDependencies {
        @Deprecated("Specify dependency directly")
        val components = DesktopComponentsDependencies

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val common = composeDependency("org.jetbrains.compose.desktop:desktop")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-linux-x64:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val linux_x64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-linux-x64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-linux-arm64:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val linux_arm64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-linux-arm64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-windows-x64:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val windows_x64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-windows-x64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-windows-arm64:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val windows_arm64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-windows-arm64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-macos-x64:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val macos_x64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-macos-x64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.desktop:desktop-jvm-macos-arm64:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val macos_arm64 = composeDependency("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-test-junit4:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val uiTestJUnit4 get() = composeDependency("org.jetbrains.compose.ui:ui-test-junit4")

        val currentOs by lazy {
            composeDependency("org.jetbrains.compose.desktop:desktop-jvm-${currentTarget.id}")
        }
    }

    @Deprecated("Specify dependency directly")
    object CommonComponentsDependencies {
        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.components:components-resources:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val resources = composeDependency("org.jetbrains.compose.components:components-resources")

        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.ui:ui-tooling-preview:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        val uiToolingPreview = composeDependency("org.jetbrains.compose.components:components-ui-tooling-preview")
    }

    object DesktopComponentsDependencies {
        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.components:components-splitpane:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        @ExperimentalPotassiumLibrary
        val splitPane = composeDependency("org.jetbrains.compose.components:components-splitpane")

        @Suppress("MaxLineLength")
        @Deprecated(
            "Specify dependency directly",
            replaceWith =
                ReplaceWith(
                    "\"org.jetbrains.compose.components:components-animatedimage:\${PotassiumBuildConfig.composeVersion}\"",
                ),
        )
        @ExperimentalPotassiumLibrary
        val animatedImage = composeDependency("org.jetbrains.compose.components:components-animatedimage")
    }
}

fun RepositoryHandler.jetbrainsCompose(): MavenArtifactRepository =
    maven { repo -> repo.setUrl("https://packages.jetbrains.team/maven/p/cmp/dev") }

fun KotlinDependencyHandler.compose(groupWithArtifact: String) = composeDependency(groupWithArtifact)

fun DependencyHandler.compose(groupWithArtifact: String) = composeDependency(groupWithArtifact)

private fun composeDependency(groupWithArtifact: String) = "$groupWithArtifact:$composeVersion"

private fun composeMaterial3Dependency(groupWithArtifact: String) = "$groupWithArtifact:$composeMaterial3Version"

private fun setUpGroovyDslExtensions(project: Project) {
    project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
        (project.extensions.getByName("kotlin") as? ExtensionAware)?.apply {
            if (extensions.findByName("potassium") == null) {
                extensions.add("potassium", PotassiumPlugin.Dependencies(project))
            }
        }
    }
    (project.repositories as? ExtensionAware)?.extensions?.apply {
        if (findByName("jetbrainsCompose") == null) {
            add(
                "jetbrainsCompose",
                object : Closure<MavenArtifactRepository>(project.repositories) {
                    fun doCall(): MavenArtifactRepository = project.repositories.jetbrainsCompose()
                },
            )
        }
    }
}
