/*
 * Copyright 2026 Sean Proctor and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal.validation

import com.seanproctor.potassium.internal.JvmApplicationContext
import com.seanproctor.potassium.internal.resolvedPackageVersion
import org.gradle.api.GradleException

// Official SemVer 2.0.0 grammar (https://semver.org): MAJOR.MINOR.PATCH with optional
// -prerelease and +build metadata. Potassium formats this single version per target, so it must
// be valid SemVer to begin with.
private val SEMVER_REGEX =
    Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)" +
            "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$",
    )

/**
 * Fails the build when the resolved application version is not a valid SemVer 2.0.0 string.
 *
 * The single `packageVersion` (falling back to the Gradle project version) is formatted per target
 * to match electron-builder's rules, so it must be valid SemVer up front.
 */
internal fun JvmApplicationContext.validatePackageVersion() {
    val version = resolvedPackageVersion().get()
    if (!SEMVER_REGEX.matches(version)) {
        throw GradleException(
            "Invalid packageVersion '$version': it must be a valid SemVer 2.0.0 version, " +
                "e.g. '1.2.3' or '1.2.3-beta.1'. Set it via `potassium { packageVersion = \"...\" }` " +
                "or the Gradle project version.",
        )
    }
}
