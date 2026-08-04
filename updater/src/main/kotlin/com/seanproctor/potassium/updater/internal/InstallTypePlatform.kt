package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.runtime.Platform

/**
 * The platform an install format can exist on, or null when it is not tied to one.
 *
 * Archives are produced for more than one platform (macOS updates apply a ZIP, and the same
 * formats are offered on Windows and Linux), and [InstallType.DEV] is not an install at all — so
 * neither constrains where the app is running.
 */
internal fun InstallType.requiredPlatform(): Platform? =
    when (this) {
        InstallType.EXE,
        InstallType.MSI,
        InstallType.NSIS,
        InstallType.NSIS_WEB,
        InstallType.PORTABLE,
        InstallType.APPX,
        -> Platform.Windows

        InstallType.DMG,
        InstallType.PKG,
        -> Platform.MacOS

        InstallType.DEB,
        InstallType.RPM,
        InstallType.SNAP,
        InstallType.FLATPAK,
        InstallType.APPIMAGE,
        -> Platform.Linux

        InstallType.ZIP,
        InstallType.TAR,
        InstallType.SEVEN_Z,
        InstallType.DEV,
        -> null
    }
