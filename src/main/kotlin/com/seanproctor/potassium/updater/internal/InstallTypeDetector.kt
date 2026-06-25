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
    private fun packageType(): InstallType? {
        val markerFile =
            resourceDirCandidates()
                .map { "$it/$PACKAGE_TYPE_FILE" }
                .firstOrNull { env.fileExists(it) }
                ?: return null
        return InstallType.fromId(env.readText(markerFile))
    }

    /**
     * Candidate locations for the app's `resources/` directory at runtime. electron-builder
     * writes `package-type` into the packaged app's resources dir; under jpackage the app root
     * is derived from `java.home` (`.../lib/runtime` → app root, or `…\runtime` on Windows) or
     * the launcher path. Only path arithmetic happens here — existence/reads go through [env].
     */
    private fun resourceDirCandidates(): List<String> =
        buildList {
            env.systemProperty("java.home")?.let { javaHome ->
                val runtimeDir = File(javaHome)
                runtimeDir.parentFile?.parentFile?.let { add("${it.path}/resources") }
                runtimeDir.parentFile?.let { add("${it.path}/resources") }
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
