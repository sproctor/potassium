package com.seanproctor.potassium.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotarizationMetadataTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `deleteStaleBlockMap removes the sidecar next to the stapled artifact`() {
        val dir = tempFolder.newFolder()
        val dmg = dir.resolve("MyApp-1.0.0-mac-arm64.dmg").apply { writeText("dmg bytes") }
        val blockMap = dir.resolve("MyApp-1.0.0-mac-arm64.dmg.blockmap").apply { writeText("stale") }

        assertTrue(AbstractNotarizationTask.deleteStaleBlockMap(dmg))

        assertFalse(blockMap.exists())
        assertTrue(dmg.exists())
    }

    @Test
    fun `deleteStaleBlockMap is a no-op without a sidecar`() {
        val dir = tempFolder.newFolder()
        val dmg = dir.resolve("MyApp-1.0.0-mac-arm64.dmg").apply { writeText("dmg bytes") }

        assertFalse(AbstractNotarizationTask.deleteStaleBlockMap(dmg))
    }

    @Test
    fun `updateYamlEntry rewrites sha512 and size but preserves blockMapSize`() {
        val yaml =
            """
            version: 1.0.0
            files:
              - url: MyApp-1.0.0-mac-arm64.zip
                sha512: oldZipHash
                size: 100
                blockMapSize: 42
              - url: MyApp-1.0.0-mac-arm64.dmg
                sha512: oldDmgHash
                size: 200
            path: MyApp-1.0.0-mac-arm64.zip
            sha512: oldZipHash
            releaseDate: '2026-08-01T10:00:00.000Z'
            """.trimIndent()

        val updated =
            AbstractNotarizationTask.updateYamlEntry(
                yaml = yaml,
                fileName = "MyApp-1.0.0-mac-arm64.dmg",
                newHash = "newDmgHash",
                newSize = 222,
            )

        val expected =
            """
            version: 1.0.0
            files:
              - url: MyApp-1.0.0-mac-arm64.zip
                sha512: oldZipHash
                size: 100
                blockMapSize: 42
              - url: MyApp-1.0.0-mac-arm64.dmg
                sha512: newDmgHash
                size: 222
            path: MyApp-1.0.0-mac-arm64.zip
            sha512: oldZipHash
            releaseDate: '2026-08-01T10:00:00.000Z'
            """.trimIndent()
        assertEquals(expected, updated)
    }
}
