package com.seanproctor.nucleus.updater

/**
 * Picks the release artifact matching the running install from a manifest's file list,
 * given the detected [InstallType].
 *
 * When the type is known, the artifact is matched by [InstallType.match]. When it is
 * [InstallType.UNKNOWN] — e.g. a release that ships a single installer per OS, where no
 * disambiguation is possible or needed — selection falls back to the platform's default
 * extension priority. This mirrors the updater-runtime `FileSelector` contract, so a
 * marker-less build still selects correctly as long as it ships one self-updatable format
 * per OS.
 */
object UpdateArtifactSelector {
    /** Returns the best-matching filename from [files], or null if none match. */
    fun select(files: List<String>, installType: InstallType): String? {
        if (files.isEmpty()) return null

        if (installType != InstallType.UNKNOWN) {
            files.firstOrNull { installType.match.matches(it) }?.let { return it }
        }

        val platform = installType.platform.takeUnless { it == Platform.Unknown } ?: Platform.current
        return platformExtensions(platform).firstNotNullOfOrNull { ext ->
            files.firstOrNull { it.lowercase().endsWith(ext) }
        }
    }

    private fun platformExtensions(platform: Platform): List<String> =
        when (platform) {
            Platform.Windows -> listOf(".exe", ".msi")
            Platform.MacOS -> listOf(".zip", ".dmg")
            Platform.Linux -> listOf(".appimage", ".deb", ".rpm", ".pacman", ".snap")
            Platform.Unknown -> emptyList()
        }
}
