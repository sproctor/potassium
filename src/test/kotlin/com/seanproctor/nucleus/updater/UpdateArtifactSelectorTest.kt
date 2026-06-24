package com.seanproctor.nucleus.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateArtifactSelectorTest {
    private val linuxFiles =
        listOf("App-1.2.3-x86_64.AppImage", "app_1.2.3_amd64.deb", "app-1.2.3.x86_64.rpm")
    private val windowsFiles = listOf("App-1.2.3-x64-nsis.exe", "App-1.2.3-x64.msi")

    @Test
    fun `deb install selects the deb`() {
        assertEquals("app_1.2.3_amd64.deb", UpdateArtifactSelector.select(linuxFiles, InstallType.DEB))
    }

    @Test
    fun `appimage install selects the AppImage`() {
        assertEquals("App-1.2.3-x86_64.AppImage", UpdateArtifactSelector.select(linuxFiles, InstallType.APPIMAGE))
    }

    @Test
    fun `nsis install selects the nsis exe not the msi`() {
        assertEquals("App-1.2.3-x64-nsis.exe", UpdateArtifactSelector.select(windowsFiles, InstallType.NSIS))
    }

    @Test
    fun `msi install selects the msi`() {
        assertEquals("App-1.2.3-x64.msi", UpdateArtifactSelector.select(windowsFiles, InstallType.MSI))
    }

    @Test
    fun `unknown linux falls back to platform default order`() {
        // Only the AppImage is present → fallback picks it without a marker.
        val single = listOf("App-1.2.3-x86_64.AppImage")
        assertEquals("App-1.2.3-x86_64.AppImage", UpdateArtifactSelector.select(single, InstallType.UNKNOWN))
    }

    @Test
    fun `empty file list returns null`() {
        assertNull(UpdateArtifactSelector.select(emptyList(), InstallType.DEB))
    }
}
