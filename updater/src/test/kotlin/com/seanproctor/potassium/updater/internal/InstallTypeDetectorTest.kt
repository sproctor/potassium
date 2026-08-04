package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.runtime.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

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

    /** Mirrors [AppResources], which appends the resources segment with a literal `/`. */
    private fun resourceKey(
        installDir: String,
        fileName: String,
    ): String = "${File(installDir).path}/$fileName"

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
                files = mapOf(resourceKey("/opt/app", "resources/package-type") to "deb\n"),
            )
        assertEquals(InstallType.DEB, detect(env))
    }

    @Test
    fun `linux reads rpm from resources package-type via launcher path`() {
        val env =
            FakeEnv(
                Platform.Linux,
                executable = "/opt/app/bin/app",
                files = mapOf(resourceKey("/opt/app/bin", "resources/package-type") to "rpm"),
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
                files = mapOf(resourceKey("/opt/app", "resources/package-type") to "pacman"),
            )
        assertNull(detect(env))
    }

    @Test
    fun `macos always resolves to zip`() {
        assertEquals(InstallType.ZIP, detect(FakeEnv(Platform.MacOS)))
    }

    @Test
    fun `windows portable env detects portable`() {
        val env =
            FakeEnv(
                Platform.Windows,
                envVars = mapOf("PORTABLE_EXECUTABLE_FILE" to "C:/Temp/App.exe"),
            )
        assertEquals(InstallType.PORTABLE, detect(env))
    }

    @Test
    fun `windows WindowsApps launcher path detects appx`() {
        val env =
            FakeEnv(
                Platform.Windows,
                executable = "C:\\Program Files\\WindowsApps\\Pkg_1.0_x64__abc\\App.exe",
            )
        assertEquals(InstallType.APPX, detect(env))
    }

    @Test
    fun `windows appx detection honors a relocated Program Files`() {
        val env =
            FakeEnv(
                Platform.Windows,
                envVars = mapOf("ProgramFiles" to "D:\\Programme"),
                executable = "D:\\Programme\\WindowsApps\\Pkg_1.0_x64__abc\\App.exe",
            )
        assertEquals(InstallType.APPX, detect(env))
    }

    @Test
    fun `windows WindowsApps elsewhere in the path is not appx`() {
        // An ordinary install into a directory that merely contains the segment. Reporting APPX
        // here would mark a self-updatable install as un-updatable.
        val env =
            FakeEnv(
                Platform.Windows,
                executable = "D:\\WindowsApps\\MyApp\\App.exe",
            )
        assertEquals(InstallType.NSIS, detect(env))
    }

    @Test
    fun `windows defaults to nsis`() {
        // The only installed Windows format the updater applies, and the only one present in the
        // manifest. MSI announces itself with a marker instead of being inferred from absence.
        assertEquals(InstallType.NSIS, detect(FakeEnv(Platform.Windows)))
    }

    @Test
    fun `windows package-type msi overrides the nsis default`() {
        val env =
            FakeEnv(
                Platform.Windows,
                properties = mapOf("java.home" to "C:/Program Files/App/runtime"),
                files = mapOf(resourceKey("C:/Program Files/App", "resources/package-type") to "msi"),
            )
        assertEquals(InstallType.MSI, detect(env))
    }

    @Test
    fun `windows package-type is read from the launcher path too`() {
        // GraalVM native images have no bundled runtime, so java.home does not locate the install.
        val env =
            FakeEnv(
                Platform.Windows,
                executable = "C:/Programs/App/App.exe",
                files = mapOf(resourceKey("C:/Programs/App", "resources/package-type") to "msi"),
            )
        assertEquals(InstallType.MSI, detect(env))
    }

    @Test
    fun `unknown platform is null`() {
        assertNull(detect(FakeEnv(Platform.Unknown)))
    }
}
