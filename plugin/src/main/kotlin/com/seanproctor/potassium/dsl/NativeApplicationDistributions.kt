/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.dsl

import org.gradle.api.Action

abstract class NativeApplicationDistributions : AbstractDistributions() {
    /** Native (Kotlin/Native macOS) packaging currently supports only [MacOSTargetFormat.Dmg]. */
    var targetFormats: Set<MacOSTargetFormat> = emptySet()

    fun targetFormats(vararg formats: MacOSTargetFormat) {
        val unsupported = formats.filter { it != MacOSTargetFormat.Dmg }
        require(unsupported.isEmpty()) {
            "nativeApplication.distributions.targetFormats supports only Dmg; got: ${unsupported.joinToString(", ")}"
        }
        targetFormats = formats.toSet()
    }

    val macOS: NativeApplicationMacOSPlatformSettings =
        objects.newInstance(
            NativeApplicationMacOSPlatformSettings::class.java,
        )

    open fun macOS(fn: Action<NativeApplicationMacOSPlatformSettings>) {
        fn.execute(macOS)
    }
}
