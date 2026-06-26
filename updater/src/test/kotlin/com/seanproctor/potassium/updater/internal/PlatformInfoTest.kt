package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.runtime.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformInfoTest {
    @Test
    fun `windows manifest has no os or arch suffix`() {
        withArch("aarch64") {
            assertEquals("latest.yml", PlatformInfo.ymlFileName("latest", Platform.Windows))
        }
    }

    @Test
    fun `macos manifest has no arch suffix regardless of host arch`() {
        withArch("aarch64") {
            assertEquals("latest-mac.yml", PlatformInfo.ymlFileName("latest", Platform.MacOS))
        }
        withArch("amd64") {
            assertEquals("latest-mac.yml", PlatformInfo.ymlFileName("latest", Platform.MacOS))
        }
    }

    @Test
    fun `linux x64 manifest has no arch suffix`() {
        withArch("amd64") {
            assertEquals("latest-linux.yml", PlatformInfo.ymlFileName("latest", Platform.Linux))
        }
    }

    @Test
    fun `linux arm64 manifest carries the arch suffix`() {
        withArch("aarch64") {
            assertEquals("latest-linux-arm64.yml", PlatformInfo.ymlFileName("latest", Platform.Linux))
        }
    }

    @Test
    fun `channel name is preserved in the file name`() {
        withArch("aarch64") {
            assertEquals("beta-linux-arm64.yml", PlatformInfo.ymlFileName("beta", Platform.Linux))
        }
        withArch("amd64") {
            assertEquals("alpha.yml", PlatformInfo.ymlFileName("alpha", Platform.Windows))
        }
    }

    /** Runs [block] with `os.arch` temporarily set to [arch], restoring the original afterward. */
    private inline fun withArch(
        arch: String,
        block: () -> Unit,
    ) {
        val original = System.getProperty("os.arch")
        System.setProperty("os.arch", arch)
        try {
            block()
        } finally {
            if (original != null) System.setProperty("os.arch", original) else System.clearProperty("os.arch")
        }
    }
}
