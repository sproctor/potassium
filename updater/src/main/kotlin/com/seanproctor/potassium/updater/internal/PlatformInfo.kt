package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.runtime.Platform

internal enum class Arch {
    X64,
    ARM64,
}

internal object PlatformInfo {
    fun currentPlatform(): Platform = Platform.Current

    fun currentArch(): Arch {
        val osArch = System.getProperty("os.arch").lowercase()
        return when {
            osArch.contains("aarch64") || osArch.contains("arm64") -> Arch.ARM64
            else -> Arch.X64
        }
    }

    /**
     * The update-manifest file name electron-updater fetches for [platform] on the current host
     * architecture, matching the channel files electron-builder publishes:
     *  - Windows: `<channel>.yml`            (no OS or arch suffix)
     *  - macOS:   `<channel>-mac.yml`         (no arch suffix — both arches share one file)
     *  - Linux:   `<channel>-linux.yml` on x64, `<channel>-linux-arm64.yml` on arm64
     *
     * Only Linux carries an architecture suffix; electron-updater fetches a single, arch-agnostic
     * file on Windows and macOS.
     */
    fun ymlFileName(
        channel: String,
        platform: Platform = currentPlatform(),
    ): String {
        val suffix = ymlSuffix(platform)
        return if (suffix.isEmpty()) "$channel.yml" else "$channel-$suffix.yml"
    }

    private fun ymlSuffix(platform: Platform): String =
        when (platform) {
            Platform.Windows, Platform.Unknown -> ""
            Platform.MacOS -> "mac"
            Platform.Linux -> "linux" + linuxArchSuffix()
        }

    private fun linuxArchSuffix(): String =
        when (currentArch()) {
            Arch.X64 -> ""
            Arch.ARM64 -> "-arm64"
        }
}
