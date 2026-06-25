/*
 * Copyright 2026 Sean Proctor and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal.validation

import com.seanproctor.potassium.internal.JvmApplicationContext
import org.gradle.api.GradleException

/**
 * Fails the build when more than one publish provider is enabled.
 *
 * electron-builder writes a single shared `<channel>-<os>.yml` per build and uploads it to every
 * configured provider. With more than one provider it lists the non-AppImage artifacts once per
 * provider in that manifest, so Potassium supports exactly one provider at a time.
 */
internal fun JvmApplicationContext.validatePublishProviders() {
    val publish = app.nativeDistributions.publish
    val enabled =
        buildList {
            if (publish.github.enabled) add("github")
            if (publish.s3.enabled) add("s3")
            if (publish.generic.enabled) add("generic")
        }
    if (enabled.size > 1) {
        throw GradleException(
            "Only one publish provider may be enabled at a time, but these are enabled: " +
                "${enabled.joinToString(", ")}. Enable just one of `github { }`, `s3 { }`, " +
                "or `generic { }` inside `publish { }`.",
        )
    }
}
