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

    /**
     * Whether the app runs from the system package root, where Windows stages AppX/MSIX packages
     * (`C:\Program Files\WindowsApps\<identity>\`).
     *
     * Anchored to the Program Files roots rather than matching `\WindowsApps\` anywhere in the
     * path: an ordinary install into a directory that happens to contain that segment — say
     * `D:\WindowsApps\MyApp\` — is not a packaged app, and treating it as one would report a
     * format that cannot self-update, silently disabling updates.
     */
    private fun isWindowsAppsInstall(): Boolean {
        val exe = env.executablePath()?.let(::normalizeSeparators) ?: return false
        return windowsAppsRoots().any { root -> exe.startsWith(root, ignoreCase = true) }
    }

    private fun windowsAppsRoots(): List<String> =
        PROGRAM_FILES_VARS
            .mapNotNull { env.getenv(it)?.takeIf(String::isNotBlank) }
            .plus(DEFAULT_PROGRAM_FILES)
            .map { "${normalizeSeparators(it).trimEnd('\\')}\\$WINDOWS_APPS_DIR\\" }
            .distinct()

    private fun normalizeSeparators(path: String): String = path.replace('/', '\\')

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
        const val WINDOWS_APPS_DIR = "WindowsApps"

        /** Program Files locations, covering 32-bit and WOW64 views as well as a relocated root. */
        val PROGRAM_FILES_VARS = listOf("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")

        /** Used alongside the environment, so detection still works if those variables are absent. */
        const val DEFAULT_PROGRAM_FILES = "C:\\Program Files"
    }
}
