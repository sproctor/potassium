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
 * On Windows the marker is written by the packaging plugin rather than electron-builder, and only
 * for MSI, which is built in its own invocation for exactly that reason — every Windows format
 * otherwise shares one staging directory, so a marker there would stamp them all alike. An MSI
 * install is therefore identified positively, which is what keeps the updater from applying the
 * NSIS installer over it.
 *
 * Absent the marker, detection reads evidence the install provides: the `PORTABLE_EXECUTABLE_FILE`
 * environment variable exported by electron-builder's portable launcher, a `WindowsApps` install
 * path (AppX/MSIX), or the `Uninstall <ProductName>.exe` that electron-builder's NSIS installer
 * writes into the install root. Reading the install directory and finding no uninstaller still
 * resolves to MSI, the only remaining installed format — that is the sole signal an MSI install
 * built before the marker leaves behind. Failing to read it at all resolves to NSIS instead:
 * absence of evidence is not evidence, and answering MSI there would disable updates silently
 * and permanently.
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
        return when (nsisUninstallerEvidence()) {
            UninstallerEvidence.FOUND -> InstallType.NSIS
            // The install directory was read and NSIS's uninstaller is not in it. For builds
            // predating the `package-type` marker this is the only signal an MSI install leaves.
            UninstallerEvidence.ABSENT -> InstallType.MSI
            // The install directory could not be read at all (restrictive ACLs, no resolvable
            // candidate). That is not evidence of anything, and resolving it to MSI would
            // silently disable updates forever, so fall back to the only Windows format this
            // updater applies and the only one electron-builder publishes to the manifest.
            UninstallerEvidence.UNKNOWN -> InstallType.NSIS
        }
    }

    /** Whether NSIS's uninstaller was found, ruled out, or could not be looked for at all. */
    private enum class UninstallerEvidence { FOUND, ABSENT, UNKNOWN }

    /** AppX/MSIX packages run from beneath the system's WindowsApps directory. */
    private fun isWindowsAppsInstall(): Boolean {
        val exe = env.executablePath() ?: return false
        return exe.replace('/', '\\').contains("\\WindowsApps\\", ignoreCase = true)
    }

    /**
     * electron-builder's NSIS installer writes `Uninstall <ProductName>.exe` next to the app
     * executable; its presence identifies an NSIS install (including nsis-web and plain exe
     * targets, which use the same template).
     *
     * A directory that cannot be listed yields [UninstallerEvidence.UNKNOWN] rather than being
     * folded into "no uninstaller" — the caller treats the two differently.
     */
    private fun nsisUninstallerEvidence(): UninstallerEvidence {
        var readAnyDirectory = false
        for (dir in installRootCandidates()) {
            val names = env.listFileNames(dir) ?: continue
            readAnyDirectory = true
            if (names.any(::isNsisUninstallerName)) return UninstallerEvidence.FOUND
        }
        return if (readAnyDirectory) UninstallerEvidence.ABSENT else UninstallerEvidence.UNKNOWN
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

/**
 * Whether [fileName] is the uninstaller electron-builder's NSIS installer writes into the install
 * root (`Uninstall <ProductName>.exe`).
 *
 * Shared deliberately: [InstallTypeDetector] matches it to recognize an NSIS install, while
 * `PlatformInstaller` matches it to avoid ever relaunching it as the app after an update. Two
 * copies drifting apart would let detection keep reporting NSIS while the relaunch again started
 * the uninstaller, which is the failure this pairing exists to prevent.
 */
internal fun isNsisUninstallerName(fileName: String): Boolean =
    fileName.startsWith("Uninstall", ignoreCase = true) && fileName.endsWith(".exe", ignoreCase = true)
