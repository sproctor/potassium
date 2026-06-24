package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.runtime.InstallType
import com.seanproctor.potassium.updater.runtime.Platform
import java.io.File

/**
 * Detects how the running application was installed, at runtime — so the Gradle plugin no
 * longer has to bake `nucleus.executable.type` into the shared prepackaged app before
 * electron-builder runs (which forces one electron-builder invocation per format, because
 * `--prepackaged` packages a single directory into every target).
 *
 * Mirrors electron-updater's runtime factory (electron-updater `src/index.ts`): platform +
 * the `resources/package-type` file electron-builder writes **per target** into deb/rpm
 * packages, plus the `APPIMAGE` / `SNAP` / `FLATPAK` environment variables.
 *
 * Every path falls back to the legacy baked marker ([InstallEnvironment.legacyType]) when no
 * runtime signal applies, so this is a safe drop-in **while the plugin still embeds the
 * marker**: nothing changes until the plugin stops baking it and starts batching formats.
 */
internal class InstallTypeDetector(
    private val env: InstallEnvironment = SystemInstallEnvironment,
) {
    /** The detected install type, or the legacy baked type when no runtime signal applies. */
    fun detect(): InstallType =
        when (env.platform) {
            Platform.Linux -> detectLinux()
            // The updater applies the ZIP on macOS regardless of dmg/zip install, so the
            // distinction never matters; treat the running app as ZIP for support checks.
            Platform.MacOS -> InstallType.ZIP
            Platform.Windows -> packageType() ?: env.legacyType()
            Platform.Unknown -> env.legacyType()
        }

    /**
     * The format id for [FileSelector] (e.g. `"deb"`, `"appimage"`, `"nsis"`), or null when the
     * type is undetermined or macOS — in which case selection falls back to the platform
     * default (which on macOS prefers the ZIP). A null here lets a marker-less, single-format
     * release still select correctly.
     */
    fun detectFormatId(): String? {
        if (env.platform == Platform.MacOS) return null
        return detect().formatId()
    }

    private fun detectLinux(): InstallType {
        // AppImage exposes its mount path via APPIMAGE (electron-updater AppImageUpdater).
        if (!env.getenv("APPIMAGE").isNullOrBlank()) return InstallType.APPIMAGE
        // Snap and Flatpak run sandboxed with telltale markers.
        if (!env.getenv("SNAP").isNullOrBlank()) return InstallType.SNAP
        if (!env.getenv("FLATPAK_ID").isNullOrBlank() || env.fileExists("/.flatpak-info")) {
            return InstallType.FLATPAK
        }
        // deb/rpm: electron-builder writes resources/package-type per target.
        return packageType() ?: env.legacyType()
    }

    /**
     * Reads electron-builder's per-target `resources/package-type` (or a nucleus-written one on
     * Windows), if present and recognized.
     */
    private fun packageType(): InstallType? {
        val markerFile =
            resourceDirCandidates()
                .map { "$it/$PACKAGE_TYPE_FILE" }
                .firstOrNull { env.fileExists(it) }
                ?: return null
        return when (env.readText(markerFile)?.trim()?.lowercase()?.removePrefix(".")) {
            "deb" -> InstallType.DEB
            "rpm" -> InstallType.RPM
            // electron-builder also writes "pacman"; nucleus has no PACMAN type yet.
            "nsis" -> InstallType.NSIS
            "nsis-web" -> InstallType.NSIS_WEB
            "msi" -> InstallType.MSI
            "exe" -> InstallType.EXE
            else -> null
        }
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

    private fun InstallType.formatId(): String? =
        when (this) {
            InstallType.EXE -> "exe"
            InstallType.NSIS -> "nsis"
            InstallType.NSIS_WEB -> "nsis-web"
            InstallType.MSI -> "msi"
            InstallType.PORTABLE -> "portable"
            InstallType.APPX -> "appx"
            InstallType.DMG -> "dmg"
            InstallType.PKG -> "pkg"
            InstallType.ZIP -> "zip"
            InstallType.DEB -> "deb"
            InstallType.RPM -> "rpm"
            InstallType.SNAP -> "snap"
            InstallType.FLATPAK -> "flatpak"
            InstallType.APPIMAGE -> "appimage"
            InstallType.TAR -> "tar"
            InstallType.SEVEN_Z -> "7z"
            InstallType.DEV -> null
        }

    private companion object {
        const val PACKAGE_TYPE_FILE = "package-type"
    }
}
