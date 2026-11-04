package com.seanproctor.potassium.updater.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdateStagingDirTest {
    private val tmpDir = File(System.getProperty("java.io.tmpdir"))

    @Test
    fun `a directory staged by the updater is its own`() {
        val dir = Files.createTempDirectory(PlatformInstaller.UPDATE_STAGING_DIR_PREFIX).toFile()
        try {
            assertTrue(PlatformInstaller.isUpdateStagingDir(dir))
        } finally {
            dir.delete()
        }
    }

    @Test
    fun `a caller's directory is not`() {
        assertFalse(PlatformInstaller.isUpdateStagingDir(File(System.getProperty("user.home"), "Downloads")))
        assertFalse(PlatformInstaller.isUpdateStagingDir(tmpDir))
        assertFalse(PlatformInstaller.isUpdateStagingDir(null))
    }

    @Test
    fun `the prefix alone outside the temp directory is not`() {
        val home = System.getProperty("user.home")
        assertFalse(PlatformInstaller.isUpdateStagingDir(File(home, "${PlatformInstaller.UPDATE_STAGING_DIR_PREFIX}1")))
    }
}
