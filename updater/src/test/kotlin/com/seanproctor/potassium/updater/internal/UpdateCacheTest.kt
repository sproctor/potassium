package com.seanproctor.potassium.updater.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleBlockMapJson =
        """{"version":"2","files":[{"name":"file","offset":0,"checksums":["a"],"sizes":[16]}]}"""

    @Test
    fun `store and read round-trip`() {
        val cache = newCache()
        val artifact = artifactFile("artifact bytes")

        cache.store(
            artifact = artifact,
            blockMapBytes = gzip(sampleBlockMapJson),
            version = "1.2.3",
            fileName = "app-1.2.3-mac-arm64.zip",
            sha512 = ChecksumVerifier.computeSha512Base64(artifact),
        )

        val entry = cache.read()
        assertNotNull(entry)
        assertEquals("1.2.3", entry!!.version)
        assertEquals("app-1.2.3-mac-arm64.zip", entry.fileName)
        assertEquals("artifact bytes", entry.artifact.readText())
        assertEquals(listOf(16L), cache.readBlockMap()!!.file.sizes)
    }

    @Test
    fun `read returns null on empty cache`() {
        assertNull(newCache().read())
    }

    @Test
    fun `tampered artifact invalidates and clears the cache`() {
        val dir = tempFolder.newFolder()
        val cache = UpdateCache(dir)
        val artifact = artifactFile("original")
        cache.store(artifact, null, "1.0.0", "app.zip", ChecksumVerifier.computeSha512Base64(artifact))

        File(dir, "current-artifact").writeText("tampered")

        assertNull(cache.read())
        assertFalse(File(dir, "cache-info").exists())
        assertFalse(File(dir, "current-artifact").exists())
    }

    @Test
    fun `missing artifact invalidates the cache`() {
        val dir = tempFolder.newFolder()
        val cache = UpdateCache(dir)
        val artifact = artifactFile("data")
        cache.store(
            artifact,
            gzip(sampleBlockMapJson),
            "1.0.0",
            "app.zip",
            ChecksumVerifier.computeSha512Base64(artifact),
        )

        File(dir, "current-artifact").delete()

        assertNull(cache.read())
    }

    @Test
    fun `store without blockmap deletes a stale blockmap`() {
        val cache = newCache()
        val first = artifactFile("first")
        cache.store(first, gzip(sampleBlockMapJson), "1.0.0", "a.zip", ChecksumVerifier.computeSha512Base64(first))
        assertNotNull(cache.readBlockMap())

        val second = artifactFile("second")
        cache.store(second, null, "2.0.0", "b.zip", ChecksumVerifier.computeSha512Base64(second))

        assertNull(cache.readBlockMap())
        assertEquals("2.0.0", cache.read()!!.version)
    }

    @Test
    fun `corrupt blockmap reads as null without breaking the artifact entry`() {
        val dir = tempFolder.newFolder()
        val cache = UpdateCache(dir)
        val artifact = artifactFile("data")
        cache.store(
            artifact,
            gzip(sampleBlockMapJson),
            "1.0.0",
            "app.zip",
            ChecksumVerifier.computeSha512Base64(artifact),
        )

        File(dir, "current.blockmap").writeText("not gzip")

        assertNull(cache.readBlockMap())
        assertNotNull(cache.read())
    }

    @Test
    fun `clear removes everything including stranded staging files`() {
        val dir = tempFolder.newFolder()
        val cache = UpdateCache(dir)
        val artifact = artifactFile("data")
        cache.store(
            artifact,
            gzip(sampleBlockMapJson),
            "1.0.0",
            "app.zip",
            ChecksumVerifier.computeSha512Base64(artifact),
        )
        // A crash mid-store can leave a .tmp behind; clear() must reclaim it too.
        File(dir, "current-artifact.tmp").writeText("stranded partial copy")

        cache.clear()

        assertNull(cache.read())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `hasEntry is a cheap existence check`() {
        val cache = newCache()
        assertFalse(cache.hasEntry())

        val artifact = artifactFile("data")
        cache.store(artifact, null, "1.0.0", "app.zip", ChecksumVerifier.computeSha512Base64(artifact))

        assertTrue(cache.hasEntry())
    }

    private fun newCache(): UpdateCache = UpdateCache(tempFolder.newFolder())

    private fun artifactFile(content: String): File {
        val file = tempFolder.newFile()
        file.writeText(content)
        return file
    }

    private fun gzip(text: String): ByteArray = BlockMapFixtures.gzip(text)
}
