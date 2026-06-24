package com.seanproctor.nucleus.updater

import java.io.File

/**
 * Detects how the running application was installed — replacing the previous approach of
 * baking a single `nucleus.executable.type` into the prepackaged app *before*
 * electron-builder runs (which forced one electron-builder invocation per format, because
 * `--prepackaged` packages one directory into every target).
 *
 * The new approach mirrors electron-updater's runtime factory (electron-updater
 * `src/index.ts`): platform + the `resources/package-type` file electron-builder writes
 * **per target** into deb/rpm/pacman packages, plus the `APPIMAGE`/`SNAP` environment
 * variables. Because no marker is baked into the shared prepackaged input, the plugin can
 * build every format of an OS in one invocation and let electron-builder own the manifest.
 *
 * Per platform:
 * - **Linux:** `APPIMAGE` → [InstallType.APPIMAGE]; `SNAP` → [InstallType.SNAP]; flatpak
 *   marker → [InstallType.FLATPAK]; else `resources/package-type` → deb/rpm/pacman; else
 *   [InstallType.UNKNOWN] (selection falls back to the platform default).
 * - **macOS:** always [InstallType.ZIP] — the updater applies the ZIP regardless of whether
 *   the user installed from a DMG or ZIP, so the distinction never affects selection.
 * - **Windows:** electron-builder writes no Windows `package-type`; honor a Nucleus-written
 *   one (to disambiguate nsis vs msi when both ship) if present, else [InstallType.NSIS]
 *   (electron-updater is always NSIS on win32).
 */
class InstallTypeDetector(
    private val env: Environment = SystemEnvironment,
) {
    /** Detects the [InstallType] of the running application. */
    fun detect(): InstallType =
        when (env.platform) {
            Platform.Linux -> detectLinux()
            Platform.MacOS -> InstallType.ZIP
            Platform.Windows -> detectWindows()
            Platform.Unknown -> InstallType.UNKNOWN
        }

    private fun detectLinux(): InstallType {
        // AppImage exposes its mount path via APPIMAGE (electron-updater AppImageUpdater).
        if (!env.getenv("APPIMAGE").isNullOrBlank()) return InstallType.APPIMAGE
        // Snap and Flatpak run sandboxed with telltale markers.
        if (!env.getenv("SNAP").isNullOrBlank()) return InstallType.SNAP
        if (!env.getenv("FLATPAK_ID").isNullOrBlank() || env.fileExists("/.flatpak-info")) {
            return InstallType.FLATPAK
        }
        // deb/rpm/pacman: electron-builder writes resources/package-type per target.
        return packageTypeMarker() ?: InstallType.UNKNOWN
    }

    private fun detectWindows(): InstallType = packageTypeMarker() ?: InstallType.NSIS

    /** Reads the `package-type` marker from the app's resources dir, if present and recognized. */
    private fun packageTypeMarker(): InstallType? {
        val markerFile =
            resourceDirCandidates()
                .map { "$it/$PACKAGE_TYPE_FILE" }
                .firstOrNull { env.fileExists(it) }
                ?: return null
        return InstallType.fromId(env.readText(markerFile))
    }

    /**
     * Candidate locations for the app's `resources/` directory at runtime. electron-builder
     * writes `package-type` into the packaged app's resources dir; under jpackage the app
     * root is derived from `java.home` (`.../lib/runtime` → app root, or `...\runtime` on
     * Windows) or from the launcher path. Only path arithmetic happens here — existence and
     * reads go through [env], so this stays testable. The real layout is confirmed against a
     * real deb/rpm/nsis build (see README open questions).
     */
    private fun resourceDirCandidates(): List<String> =
        buildList {
            env.systemProperty("java.home")?.let { javaHome ->
                val runtimeDir = File(javaHome)
                // Linux jpackage: java.home = <app>/lib/runtime ; Windows: <app>\runtime
                runtimeDir.parentFile?.parentFile?.let { add("${it.path}/resources") }
                runtimeDir.parentFile?.let { add("${it.path}/resources") }
                // macOS jpackage runtime nests deeper; probe the home dir itself too.
                add("$javaHome/resources")
            }
            env.executablePath()?.let { exe ->
                File(exe).parentFile?.let { dir ->
                    add("${dir.path}/resources")
                    dir.parentFile?.let { add("${it.path}/resources") }
                }
            }
        }.distinct()

    private companion object {
        const val PACKAGE_TYPE_FILE = "package-type"
    }
}
