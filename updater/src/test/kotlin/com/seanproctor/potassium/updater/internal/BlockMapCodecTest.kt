package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.ParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlockMapCodecTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleJson =
        """{"version":"2","files":[{"name":"file","offset":0,"checksums":["a","b"],"sizes":[10,20]}]}"""

    @Test
    fun `decodes gzip sidecar blockmap`() {
        val blockMap = BlockMapCodec.decodeGzip(BlockMapFixtures.gzip(sampleJson))

        assertEquals(listOf(10L, 20L), blockMap.file.sizes)
    }

    @Test
    fun `decodes raw-deflate embedded blockmap`() {
        val blockMap = BlockMapCodec.decodeDeflateRaw(BlockMapFixtures.deflateRaw(sampleJson.toByteArray()))

        assertEquals(listOf("a", "b"), blockMap.file.checksums)
    }

    @Test
    fun `rejects garbage gzip data`() {
        assertThrows(ParseException::class.java) {
            BlockMapCodec.decodeGzip(byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    fun `rejects truncated deflate data`() {
        val compressed = BlockMapFixtures.deflateRaw(sampleJson.toByteArray())

        assertThrows(ParseException::class.java) {
            BlockMapCodec.decodeDeflateRaw(compressed.copyOf(compressed.size / 2))
        }
    }

    @Test
    fun `reads embedded blockmap from file tail`() {
        val content = ByteArray(1000) { (it % 251).toByte() }
        val file = tempFolder.newFile("app.AppImage")
        file.writeBytes(content + BlockMapFixtures.embeddedTail(sampleJson))

        val blockMap = BlockMapCodec.readEmbedded(file)

        assertEquals(listOf(10L, 20L), blockMap.file.sizes)
    }

    @Test
    fun `rejects file without embedded blockmap`() {
        val file = tempFolder.newFile("plain.bin")
        // Trailer bytes decode to a length far larger than the file itself.
        file.writeBytes(ByteArray(64) { 0x7F })

        assertThrows(ParseException::class.java) { BlockMapCodec.readEmbedded(file) }
    }

    @Test
    fun `rejects file smaller than the trailer`() {
        val file = tempFolder.newFile("tiny.bin")
        file.writeBytes(byteArrayOf(0, 0))

        assertThrows(ParseException::class.java) { BlockMapCodec.readEmbedded(file) }
    }

    @Test
    fun `declaredEmbeddedSize reads the trailing uint32`() {
        val tail = BlockMapFixtures.embeddedTail(sampleJson)

        assertEquals((tail.size - BlockMapCodec.TRAILER_LENGTH).toLong(), BlockMapCodec.declaredEmbeddedSize(tail))
    }

    @Test
    fun `declaredEmbeddedSize rejects short input`() {
        assertThrows(ParseException::class.java) { BlockMapCodec.declaredEmbeddedSize(byteArrayOf(1, 2)) }
    }
}
