package com.seanproctor.potassium.updater.internal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * Shared helpers for building electron-builder-format blockmap fixtures. Kept in one place
 * so every test suite encodes the same wire format (gzip sidecars, raw-deflate embedded
 * tails, 24-char base64 fake checksums).
 */
internal object BlockMapFixtures {
    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun gzip(text: String): ByteArray = gzip(text.toByteArray())

    fun deflateRaw(data: ByteArray): ByteArray {
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

    /** Blockmap JSON where each segment is one chunk with a content-derived fake checksum. */
    fun blockMapJson(segments: List<ByteArray>): String {
        val checksums = segments.joinToString(",") { "\"${fakeChecksum(it)}\"" }
        val sizes = segments.joinToString(",") { it.size.toString() }
        return """{"version":"2","files":[{"name":"file","offset":0,"checksums":[$checksums],"sizes":[$sizes]}]}"""
    }

    /** Content-derived stand-in for BLAKE2b — consumers only ever compare these as strings. */
    fun fakeChecksum(segment: ByteArray): String =
        Base64
            .getEncoder()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(segment))
            .take(24)

    fun base64Sha512(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))

    /** `[deflateRaw(json)][uint32 BE length]` as electron-builder appends it to AppImages. */
    fun embeddedTail(json: String): ByteArray {
        val compressed = deflateRaw(json.toByteArray())
        return compressed + ByteBuffer.allocate(4).putInt(compressed.size).array()
    }
}
