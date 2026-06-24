package com.seanproctor.nucleus.updater

import kotlin.test.Test
import kotlin.test.assertEquals

class InstallTypeDetectorTest {
    private fun detect(env: Environment): InstallType = InstallTypeDetector(env).detect()

    @Test
    fun `linux APPIMAGE env detects AppImage`() {
        val env = FakeEnvironment(Platform.Linux, envVars = mapOf("APPIMAGE" to "/home/u/App-1.0.AppImage"))
        assertEquals(InstallType.APPIMAGE, detect(env))
    }

    @Test
    fun `linux SNAP env detects Snap`() {
        val env = FakeEnvironment(Platform.Linux, envVars = mapOf("SNAP" to "/snap/app/x1"))
        assertEquals(InstallType.SNAP, detect(env))
    }

    @Test
    fun `linux flatpak marker detects Flatpak`() {
        val env = FakeEnvironment(Platform.Linux, files = mapOf("/.flatpak-info" to "[Application]\n"))
        assertEquals(InstallType.FLATPAK, detect(env))
    }

    @Test
    fun `linux reads deb from package-type in resources`() {
        val env =
            FakeEnvironment(
                Platform.Linux,
                properties = mapOf("java.home" to "/opt/myapp/lib/runtime"),
                files = mapOf("/opt/myapp/resources/package-type" to "deb\n"),
            )
        assertEquals(InstallType.DEB, detect(env))
    }

    @Test
    fun `linux reads rpm from package-type in resources`() {
        val env =
            FakeEnvironment(
                Platform.Linux,
                properties = mapOf("java.home" to "/opt/myapp/lib/runtime"),
                files = mapOf("/opt/myapp/resources/package-type" to "rpm"),
            )
        assertEquals(InstallType.RPM, detect(env))
    }

    @Test
    fun `linux reads pacman from package-type`() {
        val env =
            FakeEnvironment(
                Platform.Linux,
                properties = mapOf("java.home" to "/opt/myapp/lib/runtime"),
                files = mapOf("/opt/myapp/resources/package-type" to "pacman"),
            )
        assertEquals(InstallType.PACMAN, detect(env))
    }

    @Test
    fun `linux resolves package-type via the launcher path`() {
        val env =
            FakeEnvironment(
                Platform.Linux,
                executable = "/opt/myapp/bin/myapp",
                files = mapOf("/opt/myapp/bin/resources/package-type" to "deb"),
            )
        assertEquals(InstallType.DEB, detect(env))
    }

    @Test
    fun `linux with no signals is unknown`() {
        val env = FakeEnvironment(Platform.Linux, properties = mapOf("java.home" to "/opt/myapp/lib/runtime"))
        assertEquals(InstallType.UNKNOWN, detect(env))
    }

    @Test
    fun `macos always resolves to zip`() {
        assertEquals(InstallType.ZIP, detect(FakeEnvironment(Platform.MacOS)))
    }

    @Test
    fun `windows defaults to nsis`() {
        assertEquals(InstallType.NSIS, detect(FakeEnvironment(Platform.Windows)))
    }

    @Test
    fun `windows honors a package-type marker for msi`() {
        val env =
            FakeEnvironment(
                Platform.Windows,
                properties = mapOf("java.home" to "C:/Program Files/MyApp/runtime"),
                files = mapOf("C:/Program Files/MyApp/resources/package-type" to "msi"),
            )
        assertEquals(InstallType.MSI, detect(env))
    }

    @Test
    fun `unknown platform is unknown`() {
        assertEquals(InstallType.UNKNOWN, detect(FakeEnvironment(Platform.Unknown)))
    }

    @Test
    fun `platform parses os name`() {
        assertEquals(Platform.Windows, Platform.fromOsName("Windows 11"))
        assertEquals(Platform.MacOS, Platform.fromOsName("Mac OS X"))
        assertEquals(Platform.Linux, Platform.fromOsName("Linux"))
        assertEquals(Platform.Unknown, Platform.fromOsName(null))
    }
}
