package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.InstallType
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
    ) : InstallEnvironment {
        override fun getenv(name: String): String? = envVars[name]

        override fun systemProperty(name: String): String? = properties[name]

        override fun fileExists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String? = files[path]

        override fun executablePath(): String? = executable
    }

    private fun detect(env: InstallEnvironment) = InstallTypeDetector(env).detect()

    @Test
    fun `linux APPIMAGE env detects AppImage`() {
        val env = FakeEnv(Platform.Linux, envVars = mapOf("APPIMAGE" to "/x/App.AppImage"))
        assertEquals(InstallType.APPIMAGE, detect(env))
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
    fun `linux undetermined is null`() {
        val env = FakeEnv(Platform.Linux, properties = mapOf("java.home" to "/opt/app/lib/runtime"))
        assertNull(detect(env))
    }

    @Test
    fun `linux unrecognized package-type is null`() {
        val env =
            FakeEnv(
                Platform.Linux,
                properties = mapOf("java.home" to "/opt/app/lib/runtime"),
                files = mapOf("/opt/app/resources/package-type" to "pacman"),
            )
        assertNull(detect(env))
    }

    @Test
    fun `macos always resolves to zip`() {
        assertEquals(InstallType.ZIP, detect(FakeEnv(Platform.MacOS)))
    }

    @Test
    fun `windows defaults to nsis`() {
        assertEquals(InstallType.NSIS, detect(FakeEnv(Platform.Windows)))
    }

    @Test
    fun `windows package-type msi overrides the default`() {
        val env =
            FakeEnv(
                Platform.Windows,
                properties = mapOf("java.home" to "C:/Program Files/App/runtime"),
                files = mapOf("C:/Program Files/App/resources/package-type" to "msi"),
            )
        assertEquals(InstallType.MSI, detect(env))
    }

    @Test
    fun `unknown platform is null`() {
        assertNull(detect(FakeEnv(Platform.Unknown)))
    }
}
