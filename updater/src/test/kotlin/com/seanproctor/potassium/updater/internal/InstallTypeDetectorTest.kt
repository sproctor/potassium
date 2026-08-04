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
        private val directories: Map<String, List<String>> = emptyMap(),
    ) : InstallEnvironment {
        override fun getenv(name: String): String? = envVars[name]

        override fun systemProperty(name: String): String? = properties[name]

        override fun fileExists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String? = files[path]

        override fun executablePath(): String? = executable

        override fun listFileNames(dirPath: String): List<String>? = directories[dirPath]
    }

    private fun detect(env: InstallEnvironment) = InstallTypeDetector(env).detect()

    /**
     * Production derives lookup keys through [File], which rewrites `/` to `\` on a Windows JVM.
     * Keys built here go through the same normalization so these tests exercise the real behavior
     * on both Windows and Linux rather than silently falling through on one of them.
     */
    private fun dirKey(path: String): String = File(path).path

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
    fun `windows nsis uninstaller in install root detects nsis`() {
        val env =
            FakeEnv(
                Platform.Windows,
                properties = mapOf("java.home" to "C:/Users/u/AppData/Local/Programs/App/runtime"),
                directories =
                    mapOf(
                        dirKey("C:/Users/u/AppData/Local/Programs/App") to listOf("App.exe", "Uninstall App.exe"),
                    ),
            )
        assertEquals(InstallType.NSIS, detect(env))
    }

    @Test
    fun `windows uninstaller found via launcher path detects nsis`() {
        val env =
            FakeEnv(
                Platform.Windows,
                executable = "C:/Programs/App/App.exe",
                directories = mapOf(dirKey("C:/Programs/App") to listOf("App.exe", "uninstall.exe")),
            )
        assertEquals(InstallType.NSIS, detect(env))
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
    fun `windows install without nsis uninstaller resolves to msi`() {
        val env =
            FakeEnv(
                Platform.Windows,
                properties = mapOf("java.home" to "C:/Program Files/App/runtime"),
                directories = mapOf(dirKey("C:/Program Files/App") to listOf("App.exe")),
            )
        assertEquals(InstallType.MSI, detect(env))
    }

    @Test
    fun `windows without any install evidence resolves to msi`() {
        assertEquals(InstallType.MSI, detect(FakeEnv(Platform.Windows)))
    }

    @Test
    fun `windows package-type wins over uninstaller evidence`() {
        // An explicitly stamped marker is a deliberate override: it must beat the evidence chain
        // even when that evidence points elsewhere. The NSIS uninstaller below is what detection
        // would otherwise key on, so this fails if the marker is ever demoted or dropped.
        val env =
            FakeEnv(
                Platform.Windows,
                properties = mapOf("java.home" to "C:/Program Files/App/runtime"),
                files = mapOf(resourceKey("C:/Program Files/App", "resources/package-type") to "msi"),
                directories = mapOf(dirKey("C:/Program Files/App") to listOf("App.exe", "Uninstall App.exe")),
            )
        assertEquals(InstallType.MSI, detect(env))
    }

    @Test
    fun `windows package-type can force nsis where evidence says otherwise`() {
        // The inverse direction, which the msi-over-nsis case alone cannot prove: without the
        // marker this install has no evidence at all and would resolve to MSI.
        val env =
            FakeEnv(
                Platform.Windows,
                properties = mapOf("java.home" to "C:/Program Files/App/runtime"),
                files = mapOf(resourceKey("C:/Program Files/App", "resources/package-type") to "nsis"),
                directories = mapOf(dirKey("C:/Program Files/App") to listOf("App.exe")),
            )
        assertEquals(InstallType.NSIS, detect(env))
    }

    @Test
    fun `unknown platform is null`() {
        assertNull(detect(FakeEnv(Platform.Unknown)))
    }
}
