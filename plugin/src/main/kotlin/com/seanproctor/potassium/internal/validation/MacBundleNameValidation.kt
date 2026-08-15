/*
 * Copyright 2026 Sean Proctor and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.internal.validation

import com.seanproctor.potassium.internal.JvmApplicationContext
import com.seanproctor.potassium.internal.macBundleNameWarnings
import com.seanproctor.potassium.internal.resolveMacBundleName

/**
 * Warns about configurations whose macOS `.app` bundle name is ambiguous.
 *
 * Reported on every host OS, not just macOS, so a Linux or Windows developer configuring a macOS
 * distribution still sees the problem.
 */
internal fun JvmApplicationContext.validateMacBundleName() {
    val dist = app.nativeDistributions
    val mac = dist.macOS
    val resolved = resolveMacBundleName(dist, mac, project.name)
    val warnings =
        macBundleNameWarnings(
            bundleName = mac.bundleName,
            appName = dist.appName,
            macPackageName = mac.packageName,
            packageName = dist.packageName,
            resolved = resolved,
        )
    for (warning in warnings) {
        project.logger.warn(warning)
    }
}
