package com.seanproctor.potassium.internal.electronbuilder

import com.seanproctor.potassium.dsl.PublishSettings
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the publish config generated for each provider. Each platform is now built in a single
 * electron-builder invocation, so electron-builder natively writes and uploads the one
 * `latest-<os>.yml` per platform for every provider — the plugin no longer suppresses S3's manifest
 * upload, so no `publishAutoUpdate: false` line is emitted for any provider.
 */
class ElectronBuilderPublishConfigTest {
    private fun publishSettings(): PublishSettings =
        ProjectBuilder.builder().build().objects.newInstance(PublishSettings::class.java)

    private fun render(publish: PublishSettings): String {
        val yaml = StringBuilder()
        ElectronBuilderConfigGenerator().generatePublishConfig(yaml, publish)
        return yaml.toString()
    }

    @Test
    fun `s3 publish never emits publishAutoUpdate`() {
        val publish = publishSettings()
        publish.s3.enabled = true
        publish.s3.bucket = "my-bucket"

        val yaml = render(publish)

        assertTrue(yaml, yaml.contains("- provider: s3"))
        assertFalse(yaml, yaml.contains("publishAutoUpdate"))
    }

    @Test
    fun `github-only publish never emits publishAutoUpdate`() {
        val publish = publishSettings()
        publish.github.enabled = true
        publish.github.owner = "owner"
        publish.github.repo = "repo"

        val yaml = render(publish)

        assertTrue(yaml, yaml.contains("- provider: github"))
        assertFalse(yaml, yaml.contains("publishAutoUpdate"))
    }

    @Test
    fun `mixed providers never emit publishAutoUpdate`() {
        val publish = publishSettings()
        publish.github.enabled = true
        publish.github.owner = "owner"
        publish.github.repo = "repo"
        publish.s3.enabled = true
        publish.s3.bucket = "my-bucket"

        val yaml = render(publish)

        assertTrue(yaml, yaml.contains("- provider: github"))
        assertTrue(yaml, yaml.contains("- provider: s3"))
        assertFalse(yaml, yaml.contains("publishAutoUpdate"))
    }
}
