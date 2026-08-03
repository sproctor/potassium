package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.ParseException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.DataFormatException
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Decodes electron-builder blockmaps from their two on-disk encodings:
 * - sidecar `<artifact>.blockmap` files are gzip-compressed JSON
 * - embedded blockmaps (AppImage) are raw-deflate JSON appended to the artifact,
 *   followed by a big-endian uint32 trailer holding the compressed length
 */
internal object BlockMapCodec {
    /** Length of the big-endian uint32 size trailer at the very end of an embedded-blockmap file. */
    const val TRAILER_LENGTH: Int = 4

    /**
     * Upper bound for compressed and decompressed blockmap payloads. Real blockmaps are a
     * few MB at most (~35 bytes of JSON per 16 KiB chunk); the cap keeps hostile or corrupt
     * server data from forcing an OutOfMemoryError, which would escape the Exception-based
     * full-download fallback.
     */
    const val MAX_BLOCKMAP_BYTES: Long = 128L * 1024 * 1024

    /** Decodes a sidecar `.blockmap`: gzip-compressed JSON. */
    fun decodeGzip(bytes: ByteArray): BlockMap {
        val jsonBytes =
            try {
                GZIPInputStream(bytes.inputStream()).use { readInflated(it) }
            } catch (e: IOException) {
                throw ParseException("Failed to decompress blockmap: ${e.message}", e)
            }
        return BlockMap.parse(jsonBytes.decodeToString())
    }

    /** Decodes an embedded blockmap: raw-deflate JSON (no zlib/gzip header). */
    fun decodeDeflateRaw(bytes: ByteArray): BlockMap {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            return BlockMap.parse(inflateBounded(inflater, bytes.size).decodeToString())
        } catch (e: DataFormatException) {
            throw ParseException("Failed to inflate embedded blockmap: ${e.message}", e)
        } finally {
            inflater.end()
        }
    }

    private fun inflateBounded(
        inflater: Inflater,
        inputSize: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(inputSize * 2)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) {
                throw ParseException("Truncated embedded blockmap data")
            }
            total += count
            if (total > MAX_BLOCKMAP_BYTES) {
                throw ParseException("Embedded blockmap exceeds the maximum decompressed size")
            }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /**
     * Reads the blockmap embedded at the tail of [file] (electron-builder AppImage layout):
     * `[content][deflateRaw(JSON)][uint32 BE = compressed length]`. The trailer is
     * self-describing, so no manifest metadata is needed to locate it.
     */
    fun readEmbedded(file: File): BlockMap {
        RandomAccessFile(file, "r").use { raf ->
            val fileSize = raf.length()
            if (fileSize <= TRAILER_LENGTH) {
                throw ParseException("File too small to contain an embedded blockmap: $fileSize bytes")
            }
            raf.seek(fileSize - TRAILER_LENGTH)
            val blockMapSize = raf.readInt().toLong() and UINT_MASK
            if (blockMapSize <= 0 || blockMapSize > fileSize - TRAILER_LENGTH || blockMapSize > MAX_BLOCKMAP_BYTES) {
                throw ParseException("Invalid embedded blockmap size: $blockMapSize (file size $fileSize)")
            }
            raf.seek(fileSize - TRAILER_LENGTH - blockMapSize)
            val data = ByteArray(blockMapSize.toInt())
            raf.readFully(data)
            return decodeDeflateRaw(data)
        }
    }

    /** The compressed-blockmap length declared by the last [TRAILER_LENGTH] bytes of [tail] (BE uint32). */
    fun declaredEmbeddedSize(tail: ByteArray): Long {
        if (tail.size < TRAILER_LENGTH) {
            throw ParseException("Trailer too short to declare a blockmap size: ${tail.size} bytes")
        }
        return ByteBuffer.wrap(tail, tail.size - TRAILER_LENGTH, TRAILER_LENGTH).int.toLong() and UINT_MASK
    }

    private fun readInflated(input: GZIPInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            if (total > MAX_BLOCKMAP_BYTES) {
                throw ParseException("Blockmap exceeds the maximum decompressed size")
            }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private const val UINT_MASK = 0xFFFFFFFFL
}
