package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.ParseException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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

    /** Decodes a sidecar `.blockmap`: gzip-compressed JSON. */
    fun decodeGzip(bytes: ByteArray): BlockMap {
        val jsonBytes =
            try {
                GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
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
            val out = ByteArrayOutputStream(bytes.size * 2)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) {
                    throw ParseException("Truncated embedded blockmap data")
                }
                out.write(buffer, 0, count)
            }
            return BlockMap.parse(out.toByteArray().decodeToString())
        } catch (e: DataFormatException) {
            throw ParseException("Failed to inflate embedded blockmap: ${e.message}", e)
        } finally {
            inflater.end()
        }
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
            if (blockMapSize <= 0 || blockMapSize > fileSize - TRAILER_LENGTH || blockMapSize > Int.MAX_VALUE) {
                throw ParseException("Invalid embedded blockmap size: $blockMapSize (file size $fileSize)")
            }
            raf.seek(fileSize - TRAILER_LENGTH - blockMapSize)
            val data = ByteArray(blockMapSize.toInt())
            raf.readFully(data)
            return decodeDeflateRaw(data)
        }
    }

    private const val UINT_MASK = 0xFFFFFFFFL
}
