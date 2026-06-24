package com.seanproctor.nucleus.updater

/** The host operating-system family. */
enum class Platform {
    Windows,
    MacOS,
    Linux,
    Unknown,
    ;

    companion object {
        /** The platform of the current JVM. */
        val current: Platform by lazy { fromOsName(System.getProperty("os.name")) }

        /** Parses an `os.name`-style string into a [Platform]. */
        fun fromOsName(osName: String?): Platform {
            val name = osName?.lowercase() ?: return Unknown
            return when {
                name.contains("win") -> Windows
                name.contains("mac") || name.contains("darwin") -> MacOS
                name.contains("nux") || name.contains("nix") || name.contains("aix") -> Linux
                else -> Unknown
            }
        }
    }
}
