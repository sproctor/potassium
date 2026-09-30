package com.seanproctor.potassium.internal

import net.coobird.thumbnailator.Thumbnails
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Reads and writes Apple Icon Image (`.icns`) files.
 *
 * An icns is an 8-byte header (`icns` + total length) followed by typed entries, each an 8-byte
 * entry header (4-byte OSType + 4-byte big-endian length that includes the header) and a payload.
 * The writer mirrors Apple's `iconutil`: the 16px and 32px 1x sizes are ARGB `ic04`/`ic05`
 * entries, every other size a PNG. Both encodings are pure JVM, so a complete icon set can be
 * produced on any build host with no macOS tooling — the macOS counterpart of what
 * `prepareLinuxIconSet` does for the hicolor sizes.
 */
internal object IcnsIcon {
    /**
     * One (point size, scale) representation macOS looks for, and every OSType that satisfies it:
     * the type this object writes first, then the equivalents other tools emit (older
     * electron-builder releases write PNG `icp4`/`icp5`; pre-10.7 icons use the `is32`/`il32`/
     * `it32` RGB types).
     */
    private class Slot(
        val label: String,
        val pixels: Int,
        val types: List<String>,
        val argb: Boolean = false,
    )

    // The small 1x sizes are ARGB, not PNG: icp4/icp5 can technically carry a PNG, but macOS
    // renders PNG data in those chunks as noise in Finder list views and the DMG title bar
    // (electron-builder#9980, fixed upstream in the icons@1.2.3 toolset).
    private val SLOTS =
        listOf(
            Slot("16x16", 16, listOf("ic04", "icp4", "is32"), argb = true),
            Slot("16x16@2x", 32, listOf("ic11")),
            Slot("32x32", 32, listOf("ic05", "icp5", "il32"), argb = true),
            Slot("32x32@2x", 64, listOf("ic12")),
            Slot("128x128", 128, listOf("ic07", "it32")),
            Slot("128x128@2x", 256, listOf("ic13")),
            Slot("256x256", 256, listOf("ic08")),
            Slot("256x256@2x", 512, listOf("ic14")),
            Slot("512x512", 512, listOf("ic09")),
            Slot("512x512@2x", 1024, listOf("ic10")),
        )

    /** Renders every representation of [source] and writes a complete icns to [target]. */
    fun write(
        source: BufferedImage,
        target: File,
    ) {
        val entries =
            SLOTS.map { slot ->
                val image = resize(source, slot.pixels)
                val payload =
                    if (slot.argb) {
                        encodeArgb(image)
                    } else {
                        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                    }
                slot.types.first() to payload
            }
        val totalLength = 8 + entries.sumOf { (_, payload) -> 8 + payload.size }
        target.outputStream().use { stream ->
            DataOutputStream(stream).use { out ->
                out.writeBytes("icns")
                out.writeInt(totalLength)
                for ((type, payload) in entries) {
                    out.writeBytes(type)
                    out.writeInt(8 + payload.size)
                    out.write(payload)
                }
            }
        }
    }

    /**
     * The representations [file] does not cover, as human-readable labels ("16x16", "256x256@2x"),
     * or an empty list for a complete file. A file this object cannot parse reports every slot as
     * missing rather than throwing: the caller only warns, and jpackage will surface a truly
     * broken file on its own.
     */
    fun missingRepresentations(file: File): List<String> {
        val present = entryTypes(file)
        return SLOTS.filter { slot -> slot.types.none { it in present } }.map { it.label }
    }

    private fun entryTypes(file: File): Set<String> {
        val data = runCatching { file.readBytes() }.getOrElse { return emptySet() }
        if (data.size < 8 || String(data, 0, 4, Charsets.US_ASCII) != "icns") return emptySet()
        // The header's total length must match the file; a mismatch means a truncated or
        // corrupt container, which counts as unparseable (every slot missing) like any other
        // malformed file.
        if (bigEndianInt(data, 4) != data.size) return emptySet()
        val types = mutableSetOf<String>()
        var offset = 8
        while (offset + 8 <= data.size) {
            val length = bigEndianInt(data, offset + 4)
            if (length < 8 || offset + length > data.size) break
            types.add(String(data, offset, 4, Charsets.US_ASCII))
            offset += length
        }
        return types
    }

    /**
     * Encodes [image] as an icns ARGB payload: the magic `ARGB`, then the alpha, red, green and
     * blue planes in that order, each compressed with [packBits]. Color is straight (not
     * premultiplied), as `iconutil` writes it and macOS composites it.
     */
    internal fun encodeArgb(image: BufferedImage): ByteArray {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val out = ByteArrayOutputStream()
        out.write("ARGB".toByteArray(Charsets.US_ASCII))
        for (shift in intArrayOf(ALPHA_SHIFT, RED_SHIFT, GREEN_SHIFT, 0)) {
            val plane = ByteArray(pixels.size) { i -> (pixels[i] ushr shift).toByte() }
            out.write(packBits(plane))
        }
        return out.toByteArray()
    }

    /**
     * The icns variant of PackBits (shared with the legacy `is32`/`il32` types). A control byte
     * `>= 0x80` repeats the next byte `control - 125` times (3..130); a control byte `< 0x80`
     * precedes `control + 1` literal bytes (1..128). A two-byte run is not representable, so
     * it is emitted as literals.
     */
    internal fun packBits(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val run = runLength(data, i, MAX_RUN)
            if (run >= MIN_RUN) {
                out.write(RUN_FLAG or (run - MIN_RUN))
                out.write(data[i].toInt())
                i += run
                continue
            }
            val start = i
            while (i < data.size && i - start < MAX_LITERAL && runLength(data, i, MIN_RUN) < MIN_RUN) i++
            out.write(i - start - 1)
            out.write(data, start, i - start)
        }
        return out.toByteArray()
    }

    /** Length of the run of bytes equal to `data[start]`, capped at [limit]. */
    private fun runLength(
        data: ByteArray,
        start: Int,
        limit: Int,
    ): Int {
        var run = 1
        while (start + run < data.size && run < limit && data[start + run] == data[start]) run++
        return run
    }

    private fun bigEndianInt(
        data: ByteArray,
        offset: Int,
    ): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun resize(
        source: BufferedImage,
        pixels: Int,
    ): BufferedImage =
        Thumbnails
            .of(source)
            .forceSize(pixels, pixels)
            .imageType(BufferedImage.TYPE_INT_ARGB)
            .asBufferedImage()

    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val RUN_FLAG = 0x80
    private const val MIN_RUN = 3
    private const val MAX_RUN = 130
    private const val MAX_LITERAL = 128
}
