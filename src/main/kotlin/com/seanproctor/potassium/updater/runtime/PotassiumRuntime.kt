package com.seanproctor.potassium.updater.runtime

import java.io.File

public enum class InstallType {
    // Windows
    EXE,
    MSI,
    NSIS,
    NSIS_WEB,
    PORTABLE,
    APPX,

    // macOS
    DMG,
    PKG,

    // Linux
    DEB,
    RPM,
    SNAP,
    FLATPAK,
    APPIMAGE,

    // Archives
    ZIP,
    TAR,
    SEVEN_Z,

    // Dev
    DEV,
}

@Suppress("TooManyFunctions")
public object PotassiumRuntime {
    public const val TYPE_PROPERTY: String = "nucleus.executable.type"
    private const val TYPE_MARKER_FILE: String = ".nucleus-executable-type"

    @JvmStatic
    public fun type(): InstallType {
        val fromProperty = System.getProperty(TYPE_PROPERTY)
        if (fromProperty != null) return parseType(fromProperty)
        return parseType(markerData?.type)
    }

    @JvmStatic
    public fun type(propertyName: String): InstallType = parseType(System.getProperty(propertyName))

    @JvmStatic
    public fun isExe(): Boolean = type() == InstallType.EXE

    @JvmStatic
    public fun isMsi(): Boolean = type() == InstallType.MSI

    @JvmStatic
    public fun isNsis(): Boolean = type() == InstallType.NSIS

    @JvmStatic
    public fun isNsisWeb(): Boolean = type() == InstallType.NSIS_WEB

    @JvmStatic
    public fun isPortable(): Boolean = type() == InstallType.PORTABLE

    @JvmStatic
    public fun isAppX(): Boolean = type() == InstallType.APPX

    @JvmStatic
    public fun isDmg(): Boolean = type() == InstallType.DMG

    @JvmStatic
    public fun isPkg(): Boolean = type() == InstallType.PKG

    @JvmStatic
    public fun isDeb(): Boolean = type() == InstallType.DEB

    @JvmStatic
    public fun isRpm(): Boolean = type() == InstallType.RPM

    @JvmStatic
    public fun isSnap(): Boolean = type() == InstallType.SNAP

    @JvmStatic
    public fun isFlatpak(): Boolean = type() == InstallType.FLATPAK

    @JvmStatic
    public fun isAppImage(): Boolean = type() == InstallType.APPIMAGE

    @JvmStatic
    public fun isZip(): Boolean = type() == InstallType.ZIP

    @JvmStatic
    public fun isTar(): Boolean = type() == InstallType.TAR

    @JvmStatic
    public fun isSevenZ(): Boolean = type() == InstallType.SEVEN_Z

    @JvmStatic
    public fun isDev(): Boolean = type() == InstallType.DEV

    @JvmStatic
    public val isGraalVmNativeImage: Boolean =
        System.getProperty("org.graalvm.nativeimage.imagecode") != null

    public fun parseType(rawValue: String?): InstallType =
        when (rawValue?.trim()?.lowercase()) {
            // Windows
            "exe", ".exe" -> InstallType.EXE
            "msi", ".msi" -> InstallType.MSI
            "nsis" -> InstallType.NSIS
            "nsis-web" -> InstallType.NSIS_WEB
            "portable" -> InstallType.PORTABLE
            "appx", ".appx" -> InstallType.APPX
            // macOS
            "dmg", ".dmg" -> InstallType.DMG
            "pkg", ".pkg" -> InstallType.PKG
            // Linux
            "deb", ".deb" -> InstallType.DEB
            "rpm", ".rpm" -> InstallType.RPM
            "snap", ".snap" -> InstallType.SNAP
            "flatpak", ".flatpak" -> InstallType.FLATPAK
            "appimage", ".appimage" -> InstallType.APPIMAGE
            // Archives
            "zip", ".zip" -> InstallType.ZIP
            "tar", "tar.gz", ".tar.gz" -> InstallType.TAR
            "7z", ".7z" -> InstallType.SEVEN_Z
            // Dev
            "dev", "development", "app-image" -> InstallType.DEV
            else -> InstallType.DEV
        }

    private data class MarkerData(
        val type: String,
        val version: String?,
    )

    private val markerData: MarkerData? by lazy { readMarkerFile() }

    /**
     * Reads the app version from the marker file written by the Gradle plugin
     * for GraalVM native-image builds (where jpackage.app-version is unavailable).
     */
    @JvmStatic
    public fun markerVersion(): String? = markerData?.version

    @Suppress("TooGenericExceptionCaught")
    private fun readMarkerFile(): MarkerData? =
        try {
            val execPath =
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null) ?: return null
            val marker = File(execPath).parentFile?.resolve(TYPE_MARKER_FILE) ?: return null
            if (!marker.isFile) return null
            val lines = marker.readLines()
            MarkerData(
                type = lines.getOrNull(0)?.trim() ?: return null,
                version = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) {
            null
        }
}
