package com.seanproctor.potassium.internal.electronbuilder

import com.seanproctor.potassium.dsl.JvmApplicationDistributions
import com.seanproctor.potassium.dsl.TargetFormat
import com.seanproctor.potassium.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MSI target used to pass only upgradeCode/perMachine, so every other installer setting fell
 * back to an electron-builder default, and neither MSI nor NSIS could set a start menu folder.
 * `windows.menuGroup`, the jpackage-era name for that folder, is the default for both.
 */
class ElectronBuilderWindowsInstallerConfigTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder
            .builder()
            .build()
            .objects
            .newInstance(JvmApplicationDistributions::class.java)

    private fun renderWindows(
        distributions: JvmApplicationDistributions,
        vararg targetFormats: TargetFormat,
    ): String {
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generateWindowsConfig(
            yaml = yaml,
            distributions = distributions,
            targetFormats = targetFormats.toList(),
            targetArch = Arch.X64,
            windowsIconOverride = null,
            executableName = "potassiumdemo",
        )
        return yaml.toString()
    }

    /** The lines of the top-level `[name]:` block. */
    private fun block(
        yaml: String,
        name: String,
    ): String =
        yaml
            .substringAfter("\n$name:\n", missingDelimiterValue = yaml.substringAfter("$name:\n"))
            .lineSequence()
            .takeWhile { it.startsWith("  ") }
            .joinToString("\n")

    @Test
    fun `msi emits electron-builder's installer defaults`() {
        val msi = block(renderWindows(distributions(), TargetFormat.Msi), "msi")

        for (line in listOf(
            "oneClick: true",
            "runAfterFinish: true",
            "createDesktopShortcut: true",
            "createStartMenuShortcut: true",
        )) {
            assertTrue(msi, msi.contains("  $line"))
        }
        assertFalse(msi, msi.contains("menuCategory"))
        assertFalse(msi, msi.contains("shortcutName"))
    }

    @Test
    fun `msi forwards the installer settings`() {
        val distributions = distributions()
        distributions.windows.msi.apply {
            oneClick = false
            runAfterFinish = false
            createDesktopShortcut = false
            createStartMenuShortcut = false
            menuCategory = "Acme"
            shortcutName = "Acme App"
        }

        val msi = block(renderWindows(distributions, TargetFormat.Msi), "msi")

        for (line in listOf(
            "oneClick: false",
            "runAfterFinish: false",
            "createDesktopShortcut: false",
            "createStartMenuShortcut: false",
            "menuCategory: \"Acme\"",
            "shortcutName: \"Acme App\"",
        )) {
            assertTrue(msi, msi.contains("  $line"))
        }
    }

    @Test
    fun `menuGroup is the default start menu folder for msi and nsis`() {
        val distributions = distributions()
        distributions.windows.menuGroup = "Tools"

        val yaml = renderWindows(distributions, TargetFormat.Msi, TargetFormat.Nsis, TargetFormat.NsisWeb)

        for (name in listOf("msi", "nsis", "nsisWeb")) {
            assertTrue(yaml, block(yaml, name).contains("  menuCategory: \"Tools\""))
        }
    }

    @Test
    fun `an explicit menuCategory wins over menuGroup`() {
        val distributions = distributions()
        distributions.windows.menuGroup = "Tools"
        distributions.windows.msi.menuCategory = "Msi Folder"
        distributions.windows.nsis.menuCategory = "Nsis Folder"

        val yaml = renderWindows(distributions, TargetFormat.Msi, TargetFormat.Nsis)

        assertTrue(yaml, block(yaml, "msi").contains("  menuCategory: \"Msi Folder\""))
        assertTrue(yaml, block(yaml, "nsis").contains("  menuCategory: \"Nsis Folder\""))
    }

    @Test
    fun `nsis forwards shortcutName and omits unset menu settings`() {
        val distributions = distributions()
        distributions.windows.nsis.shortcutName = "Acme App"

        val nsis = block(renderWindows(distributions, TargetFormat.Nsis), "nsis")

        assertTrue(nsis, nsis.contains("  shortcutName: \"Acme App\""))
        assertFalse(nsis, nsis.contains("menuCategory"))
    }

    @Test
    fun `msi settings do not leak into the nsis block`() {
        val distributions = distributions()
        distributions.windows.msi.menuCategory = "Msi Folder"
        distributions.windows.msi.shortcutName = "Msi Name"

        val nsis = block(renderWindows(distributions, TargetFormat.Msi, TargetFormat.Nsis), "nsis")

        assertFalse(nsis, nsis.contains("Msi Folder"))
        assertFalse(nsis, nsis.contains("Msi Name"))
    }
}
