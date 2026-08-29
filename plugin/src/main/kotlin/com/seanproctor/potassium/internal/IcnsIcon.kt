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
 * Every type written here accepts a PNG payload, so a complete icon set can be produced on any
 * build host with no macOS tooling — the macOS counterpart of what `prepareLinuxIconSet` does for
 * the hicolor sizes.
 */
internal object IcnsIcon {
    /**
     * One (point size, scale) representation macOS looks for, and every OSType that satisfies it:
     * the PNG-capable type this object writes first, then the equivalents other tools emit
     * (`iconutil` writes ARGB `ic04`/`ic05` for the small 1x sizes; pre-10.7 icons use the
     * `is32`/`il32`/`it32` RGB types).
     */
    private class Slot(
        val label: String,
        val pixels: Int,
        val types: List<String>,
    )

    private val SLOTS =
        listOf(
            Slot("16x16", 16, listOf("icp4", "ic04", "is32")),
            Slot("16x16@2x", 32, listOf("ic11")),
            Slot("32x32", 32, listOf("icp5", "ic05", "il32")),
            Slot("32x32@2x", 64, listOf("ic12")),
            Slot("128x128", 128, listOf("ic07", "it32")),
            Slot("128x128@2x", 256, listOf("ic13")),
            Slot("256x256", 256, listOf("ic08")),
            Slot("256x256@2x", 512, listOf("ic14")),
            Slot("512x512", 512, listOf("ic09")),
            Slot("512x512@2x", 1024, listOf("ic10")),
        )

    /** Renders every representation of [source] as PNG and writes a complete icns to [target]. */
    fun write(
        source: BufferedImage,
        target: File,
    ) {
        val entries =
            SLOTS.map { slot ->
                val png = ByteArrayOutputStream()
                ImageIO.write(resize(source, slot.pixels), "png", png)
                slot.types.first() to png.toByteArray()
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
}
