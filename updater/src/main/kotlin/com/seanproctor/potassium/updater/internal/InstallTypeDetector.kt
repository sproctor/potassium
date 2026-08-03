package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.runtime.Platform

/**
 * Detects how the running application was installed, at runtime — so the packaging plugin no
 * longer has to bake an install-type marker into the shared prepackaged app before
 * electron-builder runs (which forces one electron-builder invocation per format, because
 * `--prepackaged` packages a single directory into every target).
 *
 * Mirrors electron-updater's runtime factory (electron-updater `src/index.ts`): platform + the
 * `resources/package-type` file electron-builder writes **per target** into deb/rpm packages,
 * plus the `APPIMAGE` / `SNAP` / `FLATPAK` environment variables.
 *
 * Returns null when the type cannot be determined; selection then falls back to the platform
 * default in [FileSelector]. macOS resolves to [InstallType.ZIP] and Windows to
 * [InstallType.NSIS] — the only formats their respective updaters apply by default.
 */
internal class InstallTypeDetector(
    private val env: InstallEnvironment = SystemInstallEnvironment,
) {
    /** The detected install type, or null when it cannot be determined. */
    fun detect(): InstallType? =
        when (env.platform) {
            Platform.Linux -> detectLinux()
            Platform.MacOS -> InstallType.ZIP
            Platform.Windows -> packageType() ?: InstallType.NSIS
            Platform.Unknown -> null
        }

    private fun detectLinux(): InstallType? {
        // AppImage exposes its mount path via APPIMAGE (electron-updater AppImageUpdater).
        if (!env.getenv("APPIMAGE").isNullOrBlank()) return InstallType.APPIMAGE
        // Snap and Flatpak run sandboxed with telltale markers.
        if (!env.getenv("SNAP").isNullOrBlank()) return InstallType.SNAP
        if (!env.getenv("FLATPAK_ID").isNullOrBlank() || env.fileExists("/.flatpak-info")) {
            return InstallType.FLATPAK
        }
        // deb/rpm: electron-builder writes resources/package-type per target. Null otherwise.
        return packageType()
    }

    /**
     * Reads electron-builder's per-target `resources/package-type` (or a plugin-written one on
     * Windows), if present and recognized.
     */
    private fun packageType(): InstallType? = InstallType.fromId(AppResources.read(env, PACKAGE_TYPE_FILE))

    private companion object {
        const val PACKAGE_TYPE_FILE = "package-type"
    }
}
