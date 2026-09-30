package com.seanproctor.potassium.internal.electronbuilder

import com.seanproctor.potassium.dsl.JvmApplicationDistributions
import com.seanproctor.potassium.dsl.TargetFormat
import com.seanproctor.potassium.internal.utils.Arch
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appimage.github.io catalog flags AppImages built on the legacy runtime, which needs the
 * system's libfuse2. The generated AppImage config must select the static runtime toolset.
 */
class ElectronBuilderAppImageConfigTest {
    private fun distributions(): JvmApplicationDistributions =
        ProjectBuilder
            .builder()
            .build()
            .objects
            .newInstance(JvmApplicationDistributions::class.java)

    private fun renderLinux(vararg targetFormats: TargetFormat): String {
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generateLinuxConfig(
            yaml = yaml,
            distributions = distributions(),
            targetFormats = targetFormats.toList(),
            targetArch = Arch.X64,
            startupWMClass = null,
            linuxIconOverride = null,
            linuxAfterInstallTemplate = null,
            executableName = "potassiumdemo",
        )
        return yaml.toString()
    }

    @Test
    fun `appimage config selects the static runtime toolset`() {
        val yaml = renderLinux(TargetFormat.AppImage)

        assertTrue(yaml, yaml.contains("toolsets:\n  appimage: \"$APPIMAGE_TOOLSET_VERSION\"\n"))
    }

    @Test
    fun `other linux formats emit no appimage settings`() {
        val yaml = renderLinux(TargetFormat.Deb, TargetFormat.Rpm)

        assertFalse(yaml, yaml.contains("toolsets:"))
        assertFalse(yaml, yaml.contains("appImage:"))
    }
}
