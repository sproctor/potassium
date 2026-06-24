package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.runtime.InstallType
import com.seanproctor.potassium.updater.runtime.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallTypeDetectorTest {
    private class FakeEnv(
        override val platform: Platform,
        private val envVars: Map<String, String> = emptyMap(),
        private val properties: Map<String, String> = emptyMap(),
        private val files: Map<String, String> = emptyMap(),
        private val executable: String? = null,
        private val legacy: InstallType = InstallType.DEV,
    ) : InstallEnvironment {
        override fun getenv(name: String): String? = envVars[name]

        override fun systemProperty(name: String): String? = properties[name]

        override fun fileExists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String? = files[path]

        override fun executablePath(): String? = executable

        override fun legacyType(): InstallType = legacy
    }

    private fun detect(env: InstallEnvironment) = InstallTypeDetector(env).detect()

    private fun formatId(env: InstallEnvironment) = InstallTypeDetector(env).detectFormatId()

    @Test
    fun `linux APPIMAGE env wins over the legacy marker`() {
        val env = FakeEnv(Platform.Linux, envVars = mapOf("APPIMAGE" to "/x/App.AppImage"), legacy = InstallType.DEB)
        assertEquals(InstallType.APPIMAGE, detect(env))
        assertEquals("appimage", formatId(env))
    }

    @Test
    fun `linux SNAP env detects snap`() {
        assertEquals(InstallType.SNAP, detect(FakeEnv(Platform.Linux, envVars = mapOf("SNAP" to "/snap/app/1"))))
    }

    @Test
    fun `linux flatpak marker detects flatpak`() {
        val env = FakeEnv(Platform.Linux, files = mapOf("/.flatpak-info" to "[Application]\n"))
        assertEquals(InstallType.FLATPAK, detect(env))
    }

    @Test
    fun `linux reads deb from resources package-type via java home`() {
        val env =
            FakeEnv(
                Platform.Linux,
                properties = mapOf("java.home" to "/opt/app/lib/runtime"),
                files = mapOf("/opt/app/resources/package-type" to "deb\n"),
            )
        assertEquals(InstallType.DEB, detect(env))
        assertEquals("deb", formatId(env))
    }

    @Test
    fun `linux reads rpm from resources package-type via launcher path`() {
        val env =
            FakeEnv(
                Platform.Linux,
                executable = "/opt/app/bin/app",
                files = mapOf("/opt/app/bin/resources/package-type" to "rpm"),
            )
        assertEquals(InstallType.RPM, detect(env))
    }

    @Test
    fun `linux falls back to the legacy marker when no runtime signal`() {
        val env =
            FakeEnv(
                Platform.Linux,
                properties = mapOf("java.home" to "/opt/app/lib/runtime"),
                legacy = InstallType.DEB,
            )
        assertEquals(InstallType.DEB, detect(env))
        assertEquals("deb", formatId(env))
    }

    @Test
    fun `linux undetectable yields dev and a null format for platform fallback`() {
        val env = FakeEnv(Platform.Linux, legacy = InstallType.DEV)
        assertEquals(InstallType.DEV, detect(env))
        assertNull(formatId(env))
    }

    @Test
    fun `macos always resolves to zip with a null format`() {
        val env = FakeEnv(Platform.MacOS, legacy = InstallType.DMG)
        assertEquals(InstallType.ZIP, detect(env))
        assertNull(formatId(env))
    }

    @Test
    fun `windows defaults to the legacy marker`() {
        val env = FakeEnv(Platform.Windows, legacy = InstallType.NSIS)
        assertEquals(InstallType.NSIS, detect(env))
        assertEquals("nsis", formatId(env))
    }

    @Test
    fun `windows package-type msi overrides the legacy marker`() {
        val env =
            FakeEnv(
                Platform.Windows,
                properties = mapOf("java.home" to "C:/Program Files/App/runtime"),
                files = mapOf("C:/Program Files/App/resources/package-type" to "msi"),
                legacy = InstallType.NSIS,
            )
        assertEquals(InstallType.MSI, detect(env))
        assertEquals("msi", formatId(env))
    }
}
