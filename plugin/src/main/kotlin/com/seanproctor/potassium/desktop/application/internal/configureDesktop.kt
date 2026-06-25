/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.desktop.application.internal

import com.seanproctor.potassium.PotassiumExtension
import com.seanproctor.potassium.desktop.tasks.AbstractUnpackDefaultApplicationResourcesTask
import com.seanproctor.potassium.internal.utils.registerTask
import org.gradle.api.Project

internal fun configureDesktop(
    project: Project,
    potassiumExtension: PotassiumExtension,
) {
    if (potassiumExtension.isJvmApplicationInitialized) {
        val appInternal = potassiumExtension.application as JvmApplicationInternal
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
