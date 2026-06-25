/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package com.seanproctor.potassium.dsl

import com.seanproctor.potassium.PotassiumInternal
import com.seanproctor.potassium.internal.utils.OS
import com.seanproctor.potassium.internal.utils.currentOS

enum class PackagingBackend {
    /** App-image creation only (jpackage). */
    JPACKAGE,

    /** Full packaging via electron-builder --prepackaged. */
    ELECTRON_BUILDER,
}

/**
 * OS-agnostic union of every packaging format the pipeline understands, used internally by the
 * tasks and config generator. **Consumers should not use this directly** — the DSL exposes formats
 * grouped per OS as [MacOSTargetFormat] / [WindowsTargetFormat] / [LinuxTargetFormat], each of which
 * maps to one of these. Marked [PotassiumInternal] to enforce that.
 */
@PotassiumInternal
enum class TargetFormat(
    internal val id: String,
    internal val targetOS: OS,
    val backend: PackagingBackend,
) {
    // --- Formats using jpackage (app-image only) ---
    JpackageImage("app-image", currentOS, PackagingBackend.JPACKAGE),

    // --- Existing formats migrated to electron-builder ---
    Pkg("pkg", OS.MacOS, PackagingBackend.ELECTRON_BUILDER),
    Deb("deb", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Rpm("rpm", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Dmg("dmg", OS.MacOS, PackagingBackend.ELECTRON_BUILDER),
    Exe("exe", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    Msi("msi", OS.Windows, PackagingBackend.ELECTRON_BUILDER),

    // --- New formats (electron-builder only) ---
    Nsis("nsis", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    NsisWeb("nsis-web", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    Portable("portable", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    AppX("appx", OS.Windows, PackagingBackend.ELECTRON_BUILDER),
    AppImage("AppImage", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Snap("snap", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Flatpak("flatpak", OS.Linux, PackagingBackend.ELECTRON_BUILDER),
    Zip("zip", currentOS, PackagingBackend.ELECTRON_BUILDER),
    Tar("tar.gz", currentOS, PackagingBackend.ELECTRON_BUILDER),
    SevenZ("7z", currentOS, PackagingBackend.ELECTRON_BUILDER),
    ;

    val isCompatibleWithCurrentOS: Boolean by lazy { isCompatibleWith(currentOS) }

    /** Whether this format is a store format that requires sandboxing (App Store, Windows Store, Flatpak). */
    val isStoreFormat: Boolean
        get() = this in setOf(Pkg, AppX, Flatpak)

    internal fun isCompatibleWith(os: OS): Boolean = os == targetOS

    val outputDirName: String
        get() = if (this == JpackageImage) "app" else id

    val fileExt: String
        get() {
            check(this != JpackageImage) { "$this cannot have a file extension" }
            return ".$id"
        }

    /**
     * The electron-builder target name used in CLI arguments.
     * Maps this format to the target identifier expected by electron-builder.
     */
    internal val electronBuilderTarget: String
        get() =
            when (this) {
                Exe, Nsis -> "nsis"
                NsisWeb -> "nsis-web"
                Tar -> "tar.gz"
                SevenZ -> "7z"
                JpackageImage -> error("JpackageImage uses jpackage, not electron-builder")
                else -> id
            }
}

/** macOS installer/archive formats, selectable via `potassium { macOS { targetFormats(...) } }`. */
enum class MacOSTargetFormat(
    internal val format: TargetFormat,
) {
    Dmg(TargetFormat.Dmg),
    Pkg(TargetFormat.Pkg),
    Zip(TargetFormat.Zip),
    Tar(TargetFormat.Tar),
    SevenZ(TargetFormat.SevenZ),
}

/** Windows installer/archive formats, selectable via `potassium { windows { targetFormats(...) } }`. */
enum class WindowsTargetFormat(
    internal val format: TargetFormat,
) {
    Exe(TargetFormat.Exe),
    Msi(TargetFormat.Msi),
    Nsis(TargetFormat.Nsis),
    NsisWeb(TargetFormat.NsisWeb),
    Portable(TargetFormat.Portable),
    AppX(TargetFormat.AppX),
    Zip(TargetFormat.Zip),
    Tar(TargetFormat.Tar),
    SevenZ(TargetFormat.SevenZ),
}

/** Linux installer/archive formats, selectable via `potassium { linux { targetFormats(...) } }`. */
enum class LinuxTargetFormat(
    internal val format: TargetFormat,
) {
    Deb(TargetFormat.Deb),
    Rpm(TargetFormat.Rpm),
    AppImage(TargetFormat.AppImage),
    Snap(TargetFormat.Snap),
    Flatpak(TargetFormat.Flatpak),
    Zip(TargetFormat.Zip),
    Tar(TargetFormat.Tar),
    SevenZ(TargetFormat.SevenZ),
}
