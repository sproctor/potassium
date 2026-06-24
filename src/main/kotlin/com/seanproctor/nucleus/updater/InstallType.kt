package com.seanproctor.nucleus.updater

/**
 * How the application was installed — i.e. which artifact format is currently running.
 *
 * Constructor params (positional):
 * - [id]: the canonical marker value. Matches what electron-builder writes to
 *   `resources/package-type` (`deb`/`rpm`/`pacman`) and the historical Nucleus
 *   `nucleus.executable.type` values.
 * - [platform]: the OS this format belongs to.
 * - [selfUpdatable]: whether the in-app updater can apply an update of this format.
 * - [match]: how the matching release artifact is recognized in an update manifest's
 *   file list (mirrors the updater-runtime `FileSelector`).
 */
enum class InstallType(
    val id: String,
    val platform: Platform,
    val selfUpdatable: Boolean,
    val match: ArtifactMatch,
) {
    // --- Windows ---
    NSIS("nsis", Platform.Windows, true, ArtifactMatch.Suffix("-nsis.")),
    NSIS_WEB("nsis-web", Platform.Windows, true, ArtifactMatch.Suffix("-nsis.")),
    EXE("exe", Platform.Windows, true, ArtifactMatch.Suffix("-nsis.")),
    MSI("msi", Platform.Windows, true, ArtifactMatch.Extension(".msi")),
    PORTABLE("portable", Platform.Windows, false, ArtifactMatch.Suffix("-portable.")),
    APPX("appx", Platform.Windows, false, ArtifactMatch.Extension(".appx")),

    // --- macOS ---
    DMG("dmg", Platform.MacOS, true, ArtifactMatch.Extension(".dmg")),
    ZIP("zip", Platform.MacOS, true, ArtifactMatch.Extension(".zip")),
    PKG("pkg", Platform.MacOS, false, ArtifactMatch.Extension(".pkg")),

    // --- Linux ---
    APPIMAGE("appimage", Platform.Linux, true, ArtifactMatch.Extension(".appimage")),
    DEB("deb", Platform.Linux, true, ArtifactMatch.Extension(".deb")),
    RPM("rpm", Platform.Linux, true, ArtifactMatch.Extension(".rpm")),
    PACMAN("pacman", Platform.Linux, true, ArtifactMatch.Extension(".pacman")),
    SNAP("snap", Platform.Linux, false, ArtifactMatch.Extension(".snap")),
    FLATPAK("flatpak", Platform.Linux, false, ArtifactMatch.Extension(".flatpak")),

    // --- Unknown / dev ---
    UNKNOWN("unknown", Platform.Unknown, false, ArtifactMatch.None),
    ;

    companion object {
        /** Resolves an [InstallType] from a raw marker/id string, or null if unrecognized. */
        fun fromId(raw: String?): InstallType? {
            val key = raw?.trim()?.lowercase()?.removePrefix(".") ?: return null
            return entries.firstOrNull { it.id == key }
        }
    }
}

/** How a release artifact filename is matched to an [InstallType]. */
sealed interface ArtifactMatch {
    fun matches(fileName: String): Boolean

    /** Matches a filename fragment — disambiguates formats that share `.exe` (`-nsis.`, `-portable.`). */
    data class Suffix(val value: String) : ArtifactMatch {
        override fun matches(fileName: String): Boolean = fileName.lowercase().contains(value)
    }

    /** Matches a file extension (e.g. `.deb`). */
    data class Extension(val value: String) : ArtifactMatch {
        override fun matches(fileName: String): Boolean = fileName.lowercase().endsWith(value)
    }

    /** Never matches (UNKNOWN / dev). */
    object None : ArtifactMatch {
        override fun matches(fileName: String): Boolean = false
    }
}
