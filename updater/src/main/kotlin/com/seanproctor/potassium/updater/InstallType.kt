package com.seanproctor.potassium.updater

public enum class InstallType(
    public val id: String,
) {
    // Windows
    EXE("exe"),
    MSI("msi"),
    NSIS("nsis"),
    NSIS_WEB("nsis-web"),
    PORTABLE("portable"),
    APPX("appx"),

    // macOS
    DMG("dmg"),
    PKG("pkg"),

    // Linux
    DEB("deb"),
    RPM("rpm"),
    SNAP("snap"),
    FLATPAK("flatpak"),
    APPIMAGE("appimage"),

    // Archives
    ZIP("zip"),
    TAR("tar"),
    SEVEN_Z("7z"),

    // Dev
    DEV("dev"),
    ;

    public companion object {
        /** Resolves an [InstallType] from its [id] (case-insensitive, a leading `.` is allowed), or null. */
        public fun fromId(id: String?): InstallType? {
            val key = id?.trim()?.lowercase()?.removePrefix(".") ?: return null
            return entries.firstOrNull { it.id == key }
        }
    }
}
