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
 * On Windows the marker is written by the packaging plugin rather than electron-builder, and only
 * for MSI, which is built in its own invocation for exactly that reason — every Windows format
 * otherwise shares one staging directory, so a marker there would stamp them all alike. An MSI
 * install is therefore identified positively, which is what keeps the updater from applying the
 * NSIS installer over it.
 *
 * The rest of Windows detection reads what the install exports about itself:
 * `PORTABLE_EXECUTABLE_FILE` from electron-builder's portable launcher, or a `WindowsApps` path
 * for AppX/MSIX. Anything else is NSIS — the only remaining installed format this updater applies
 * and the only one electron-builder publishes to the manifest. Nothing is inferred from what an
 * install *lacks*, because MSI is the only format that inference was ever for and it now
 * identifies itself.
 *
 * Installs produced before the marker existed carry none, so an MSI made by an older build still
 * resolves to NSIS.
 *
 * Returns null when the type cannot be determined (Linux without any marker); selection then
 * falls back to the platform default in [FileSelector]. macOS resolves to [InstallType.ZIP] —
 * the only format its updater applies.
 */
internal class InstallTypeDetector(
    private val env: InstallEnvironment = SystemInstallEnvironment,
) {
    /** The detected install type, or null when it cannot be determined. */
    fun detect(): InstallType? =
        when (env.platform) {
            Platform.Linux -> detectLinux()
            Platform.MacOS -> InstallType.ZIP
            Platform.Windows -> detectWindows()
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

    private fun detectWindows(): InstallType {
        packageType()?.let { return it }
        // electron-builder's portable launcher exports this before starting the app.
        if (!env.getenv("PORTABLE_EXECUTABLE_FILE").isNullOrBlank()) return InstallType.PORTABLE
        if (isWindowsAppsInstall()) return InstallType.APPX
        // Everything else is NSIS: it is the only remaining installed format this updater applies
        // and the only one electron-builder publishes to the manifest. MSI says so with its
        // marker, so nothing here has to be inferred from what the install *lacks* — an inference
        // that could only ever be a guess, and one that silently disabled updates when wrong.
        return InstallType.NSIS
    }

    /** AppX/MSIX packages run from beneath the system's WindowsApps directory. */
    private fun isWindowsAppsInstall(): Boolean {
        val exe = env.executablePath() ?: return false
        return exe.replace('/', '\\').contains("\\WindowsApps\\", ignoreCase = true)
    }

    /**
     * Reads `resources/package-type`, if present and recognized.
     *
     * electron-builder writes it from its Linux fpm target only (deb/rpm/pacman, and only when a
     * publish config is configured), immediately before packaging each target — which is why the
     * value is per-package there despite the staging directory being shared. On other platforms
     * no tool writes it; it is honored anyway so a build that stamps its own marker overrides the
     * evidence-based detection.
     */
    private fun packageType(): InstallType? = InstallType.fromId(AppResources.read(env, PACKAGE_TYPE_FILE))

    private companion object {
        const val PACKAGE_TYPE_FILE = "package-type"
    }
}
