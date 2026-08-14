/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

import com.seanproctor.potassium.PotassiumExtension
import com.seanproctor.potassium.internal.utils.registerTask
import com.seanproctor.potassium.tasks.AbstractUnpackDefaultApplicationResourcesTask
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware

internal fun configureDesktop(
    project: Project,
    potassiumExtension: PotassiumExtension,
) {
    if (potassiumExtension.isJvmApplicationInitialized) {
        checkNoComposeDesktopApplication(project)
        val appInternal = potassiumExtension.jvmApplication
        val defaultBuildType = appInternal.data.buildTypes.default
        val appData = JvmApplicationContext(project, appInternal, defaultBuildType)
        appData.configureJvmApplication()

        if (appInternal.data.graalvm.isEnabled
                .getOrElse(false)
        ) {
            appData.configureGraalvmApplication()
        }
    }

    if (potassiumExtension.isNativeApplicationInitialized) {
        val unpackDefaultResources =
            project.registerTask<AbstractUnpackDefaultApplicationResourcesTask>(
                "unpackDefaultNativeApplicationResources",
            ) {}
        configureNativeApplication(project, potassiumExtension.nativeApplication, unpackDefaultResources)
    }
}

/**
 * Fails with an actionable message when both `potassium.application { }` and
 * `compose.desktop.application { }` are configured in the same project. Potassium ships its own
 * (forked) Compose Desktop packaging and registers the same task names, so letting both DSLs
 * drive packaging throws a cryptic `Cannot add task '…' as a task with that name already exists`.
 * The Compose plugin itself can stay applied (for Hot Reload, IDE integration,
 * `compose.desktop.currentOs`, …) — only its `application { }` packaging block must be removed.
 */
private fun checkNoComposeDesktopApplication(project: Project) {
    val compose = project.extensions.findByName("compose") as? ExtensionAware ?: return
    val desktop = compose.extensions.findByName("desktop") as? ExtensionAware ?: return
    if (!isComposeJvmApplicationInitialized(desktop)) return
    error(
        "Both `potassium.application { }` and `compose.desktop.application { }` are configured in " +
            "project '${project.path}'. Potassium replaces Compose Desktop's packaging and registers " +
            "the same Gradle tasks, so the two blocks conflict. Remove the " +
            "`compose.desktop.application { }` block and configure packaging via " +
            "`potassium.application { }` instead — the Compose plugin can stay applied for Hot Reload " +
            "and IDE integration.",
    )
}

/**
 * Reads Compose's internal `_isJvmApplicationInitialized` flag without initializing the lazy
 * `application` extension (unlike calling `getApplication`, which would trigger the Compose plugin
 * to register its packaging tasks — the very tasks this check exists to keep out of the build).
 * Returns false if the flag cannot be read.
 */
private fun isComposeJvmApplicationInitialized(desktop: ExtensionAware): Boolean =
    runCatching {
        desktop.javaClass
            .getMethod("get_isJvmApplicationInitialized\$compose")
            .invoke(desktop) as? Boolean
            ?: false
    }.getOrDefault(false)
