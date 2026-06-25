/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

import com.seanproctor.potassium.PotassiumExtension
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinJsProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal val Project.potassiumExt: PotassiumExtension?
    get() = extensions.findByType(PotassiumExtension::class.java)

internal val Project.mppExt: KotlinMultiplatformExtension
    get() = mppExtOrNull ?: error("Could not find KotlinMultiplatformExtension ($project)")

internal val Project.mppExtOrNull: KotlinMultiplatformExtension?
    get() = extensions.findByType(KotlinMultiplatformExtension::class.java)

internal val Project.kotlinJvmExt: KotlinJvmProjectExtension
    get() = kotlinJvmExtOrNull ?: error("Could not find KotlinJvmProjectExtension ($project)")

internal val Project.kotlinJvmExtOrNull: KotlinJvmProjectExtension?
    get() = extensions.findByType(KotlinJvmProjectExtension::class.java)

internal val Project.kotlinJsExtOrNull: KotlinJsProjectExtension?
    get() = extensions.findByType(KotlinJsProjectExtension::class.java)

internal val Project.javaSourceSets: SourceSetContainer
    get() = extensions.getByType(JavaPluginExtension::class.java).sourceSets
