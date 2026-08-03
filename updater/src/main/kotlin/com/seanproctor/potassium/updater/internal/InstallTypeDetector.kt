package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.runtime.Platform
import java.io.File

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
 * Windows has no per-target marker (all formats package the same shared directory), so
 * detection reads evidence the install itself provides: the `PORTABLE_EXECUTABLE_FILE`
 * environment variable exported by electron-builder's portable launcher, a `WindowsApps`
 * install path (AppX/MSIX), or the `Uninstall <ProductName>.exe` that electron-builder's NSIS
 * installer writes into the install root. An install with none of these is resolved as MSI —
 * the only remaining installed Windows format — which the updater treats as a managed
 * deployment and does not self-update.
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
        if (hasNsisUninstaller()) return InstallType.NSIS
        // Installed without NSIS's uninstaller: MSI is the only remaining installed format.
        return InstallType.MSI
    }

    /** AppX/MSIX packages run from beneath the system's WindowsApps directory. */
    private fun isWindowsAppsInstall(): Boolean {
        val exe = env.executablePath() ?: return false
        return exe.replace('/', '\\').contains("\\WindowsApps\\", ignoreCase = true)
    }

    /**
     * electron-builder's NSIS installer writes `Uninstall <ProductName>.exe` next to the app
     * executable; its presence identifies an NSIS install (including nsis-web and plain exe
     * targets, which use the same template).
     */
    private fun hasNsisUninstaller(): Boolean =
        installRootCandidates().any { dir ->
            env.listFileNames(dir).orEmpty().any { name ->
                name.startsWith("Uninstall", ignoreCase = true) && name.endsWith(".exe", ignoreCase = true)
            }
        }

    /** jpackage layout: the launcher exe sits in the install dir, with java.home = <install-dir>\runtime. */
    private fun installRootCandidates(): List<String> =
        buildList {
            env.systemProperty("java.home")?.let { javaHome ->
                File(javaHome).parentFile?.let { add(it.path) }
            }
            env.executablePath()?.let { exe ->
                File(exe).parentFile?.let { add(it.path) }
            }
        }.distinct()

    /**
     * Reads electron-builder's per-target `resources/package-type` (or a plugin-written one on
     * Windows), if present and recognized.
     */
    private fun packageType(): InstallType? = InstallType.fromId(AppResources.read(env, PACKAGE_TYPE_FILE))

    private companion object {
        const val PACKAGE_TYPE_FILE = "package-type"
    }
}
