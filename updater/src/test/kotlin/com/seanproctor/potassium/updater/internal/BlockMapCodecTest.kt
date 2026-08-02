package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.ParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

class BlockMapCodecTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val sampleJson =
        """{"version":"2","files":[{"name":"file","offset":0,"checksums":["a","b"],"sizes":[10,20]}]}"""

    @Test
    fun `decodes gzip sidecar blockmap`() {
        val blockMap = BlockMapCodec.decodeGzip(gzip(sampleJson.toByteArray()))

        assertEquals(listOf(10L, 20L), blockMap.file.sizes)
    }

    @Test
    fun `decodes raw-deflate embedded blockmap`() {
        val blockMap = BlockMapCodec.decodeDeflateRaw(deflateRaw(sampleJson.toByteArray()))

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
        val compressed = deflateRaw(sampleJson.toByteArray())

        assertThrows(ParseException::class.java) {
            BlockMapCodec.decodeDeflateRaw(compressed.copyOf(compressed.size / 2))
        }
    }

    @Test
    fun `reads embedded blockmap from file tail`() {
        val content = ByteArray(1000) { (it % 251).toByte() }
        val file = tempFolder.newFile("app.AppImage")
        file.writeBytes(content + embeddedTrailer(sampleJson))

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

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun deflateRaw(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return out.toByteArray()
    }

    /** `[deflateRaw(json)][uint32 BE length]` as electron-builder appends it. */
    private fun embeddedTrailer(json: String): ByteArray {
        val compressed = deflateRaw(json.toByteArray())
        return compressed + ByteBuffer.allocate(4).putInt(compressed.size).array()
    }
}
