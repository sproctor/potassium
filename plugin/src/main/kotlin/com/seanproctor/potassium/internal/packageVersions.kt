/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal

import com.seanproctor.potassium.internal.utils.OS
import com.seanproctor.potassium.internal.utils.currentOS
import org.gradle.api.provider.Provider

/**
 * The single application version: the configured `packageVersion`, falling back to the Gradle
 * project version, then "1.0.0". It is passed verbatim to electron-builder, which applies its own
 * per-target sanitization (NSIS/MSI strip the pre-release suffix, DEB/RPM convert `-` to `~`, etc.).
 */
internal fun JvmApplicationContext.resolvedPackageVersion(): Provider<String> =
    project.provider {
        app.nativeDistributions.packageVersion
            ?: project.version.toString().takeIf { it != "unspecified" }
            ?: "1.0.0"
    }

/**
 * Version for jpackage's `--app-version` on the current OS. jpackage validates the version per
 * platform, so we apply the same rules electron-builder uses (see [formatVersionForJpackage]).
 */
internal fun JvmApplicationContext.jpackageVersion(): Provider<String> =
    resolvedPackageVersion().map { formatVersionForJpackage(it, currentOS) }

/**
 * Formats a version for jpackage/native packaging on the given OS, mirroring electron-builder's
 * per-platform handling:
 * - Windows / macOS: drop the SemVer pre-release/build suffix, keeping the numeric `MAJOR.MINOR.PATCH`
 *   core. Windows installers and macOS `CFBundleShortVersionString` require numeric versions; this
 *   matches electron-builder's `getVersionInWeirdWindowsForm`.
 * - Linux: replace `-` with `~` (electron-builder's DEB/RPM sanitization) — a valid pre-release
 *   separator that sorts before the final release.
 */
internal fun formatVersionForJpackage(
    version: String,
    os: OS,
): String =
    when (os) {
        OS.Windows, OS.MacOS -> version.substringBefore('-').substringBefore('+')
        OS.Linux -> version.replace('-', '~')
    }
