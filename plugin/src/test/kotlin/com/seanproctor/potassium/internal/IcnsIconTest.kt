package com.seanproctor.potassium.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

class IcnsIconTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sourceImage(size: Int = 1024): BufferedImage =
        BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().run {
                color = Color(30, 120, 200)
                fillOval(0, 0, size, size)
                dispose()
            }
        }

    private fun entries(file: File): Map<String, ByteArray> {
        val data = file.readBytes()
        assertEquals("icns", String(data, 0, 4, Charsets.US_ASCII))
        val result = mutableMapOf<String, ByteArray>()
        var offset = 8
        while (offset < data.size) {
            val type = String(data, offset, 4, Charsets.US_ASCII)
            val length =
                ((data[offset + 4].toInt() and 0xFF) shl 24) or
                    ((data[offset + 5].toInt() and 0xFF) shl 16) or
                    ((data[offset + 6].toInt() and 0xFF) shl 8) or
                    (data[offset + 7].toInt() and 0xFF)
            result[type] = data.copyOfRange(offset + 8, offset + length)
            offset += length
        }
        return result
    }

    /** Decodes an icns ARGB payload back into ARGB pixels, the inverse of [IcnsIcon.encodeArgb]. */
    private fun decodeArgb(
        payload: ByteArray,
        size: Int,
    ): IntArray {
        assertEquals("ARGB", String(payload, 0, 4, Charsets.US_ASCII))
        val planes = Array(4) { ByteArray(size * size) }
        var offset = 4
        for (plane in planes) {
            var filled = 0
            while (filled < plane.size) {
                val control = payload[offset++].toInt() and 0xFF
                if (control >= 0x80) {
                    val count = control - 125
                    plane.fill(payload[offset++], filled, filled + count)
                    filled += count
                } else {
                    val count = control + 1
                    System.arraycopy(payload, offset, plane, filled, count)
                    offset += count
                    filled += count
                }
            }
        }
        assertEquals("payload must hold exactly four planes", payload.size, offset)
        return IntArray(size * size) { i ->
            planes.fold(0) { argb, plane -> (argb shl 8) or (plane[i].toInt() and 0xFF) }
        }
    }

    @Test
    fun `write produces a complete icon set at the right sizes`() {
        val target = tmp.newFile("icon.icns")
        IcnsIcon.write(sourceImage(), target)

        val argbPixels = mapOf("ic04" to 16, "ic05" to 32)
        val pngPixels =
            mapOf(
                "ic11" to 32,
                "ic12" to 64,
                "ic07" to 128,
                "ic13" to 256,
                "ic08" to 256,
                "ic14" to 512,
                "ic09" to 512,
                "ic10" to 1024,
            )
        val written = entries(target)
        assertEquals(argbPixels.keys + pngPixels.keys, written.keys)
        for ((type, size) in argbPixels) {
            // decodeArgb asserts the planes exactly fill size x size pixels.
            decodeArgb(written.getValue(type), size)
        }
        for ((type, size) in pngPixels) {
            val image = ImageIO.read(written.getValue(type).inputStream())
            assertTrue("$type payload must decode as an image", image != null)
            assertEquals("$type width", size, image.width)
            assertEquals("$type height", size, image.height)
        }
        assertTrue(IcnsIcon.missingRepresentations(target).isEmpty())
    }

    @Test
    fun `argb payload round-trips the pixels, alpha included`() {
        val image =
            BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
                for (y in 0 until 16) {
                    for (x in 0 until 16) {
                        // Mixes long runs (x < 8) with noise so both control-byte kinds are used.
                        val argb = if (x < 8) 0x00000000 else (x * 16 shl 24) or (y * 977 + x * 131)
                        setRGB(x, y, argb)
                    }
                }
            }
        val expected = image.getRGB(0, 0, 16, 16, null, 0, 16)

        val decoded = decodeArgb(IcnsIcon.encodeArgb(image), 16)

        assertTrue(expected.contentEquals(decoded))
    }

    @Test
    fun `argb encoding matches iconutil byte for byte`() {
        // The bundled default icon was produced by Apple's iconutil; re-encoding its small ARGB
        // entries' pixels must reproduce them exactly.
        val resource = javaClass.classLoader.getResourceAsStream("default-potassium-icon-mac.icns")!!
        val icns = tmp.newFile("default.icns").apply { resource.use { writeBytes(it.readBytes()) } }
        val iconutilEntries = entries(icns)

        for ((type, size) in mapOf("ic04" to 16, "ic05" to 32)) {
            val original = iconutilEntries.getValue(type)
            val image =
                BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
                    setRGB(0, 0, size, size, decodeArgb(original, size), 0, size)
                }
            assertTrue("$type re-encoding differs from iconutil", original.contentEquals(IcnsIcon.encodeArgb(image)))
        }
    }

    @Test
    fun `packBits caps runs at 130 and literals at 128`() {
        val run = ByteArray(131) { 7 }
        assertTrue(byteArrayOf(0xFF.toByte(), 7, 0, 7).contentEquals(IcnsIcon.packBits(run)))

        val literal = ByteArray(129) { (it % 2).toByte() }
        val packed = IcnsIcon.packBits(literal)
        assertEquals(127, packed[0].toInt())
        assertEquals(0, packed[129].toInt())
        assertEquals(1 + 128 + 1 + 1, packed.size)
    }

    @Test
    fun `missingRepresentations reports the slots an incomplete file lacks`() {
        // The shape that shipped in the wild: retina and large sizes present, no plain 16/32.
        val complete = tmp.newFile("complete.icns")
        IcnsIcon.write(sourceImage(64), complete)
        val kept = entries(complete).filterKeys { it != "ic04" && it != "ic05" }

        val incomplete = tmp.newFile("incomplete.icns")
        incomplete.outputStream().use { stream ->
            DataOutputStream(stream).use { out ->
                out.writeBytes("icns")
                out.writeInt(8 + kept.entries.sumOf { 8 + it.value.size })
                for ((type, payload) in kept) {
                    out.writeBytes(type)
                    out.writeInt(8 + payload.size)
                    out.write(payload)
                }
            }
        }

        assertEquals(listOf("16x16", "32x32"), IcnsIcon.missingRepresentations(incomplete))
    }

    @Test
    fun `alternative small-size types satisfy their slots`() {
        // Older electron-builder releases emit PNG icp4/icp5 rather than ARGB ic04/ic05 for the
        // 1x small sizes; both must count as present.
        val complete = tmp.newFile("complete.icns")
        IcnsIcon.write(sourceImage(64), complete)
        val renamed =
            entries(complete).mapKeys { (type, _) ->
                when (type) {
                    "ic04" -> "icp4"
                    "ic05" -> "icp5"
                    else -> type
                }
            }

        val file = tmp.newFile("png-small-sizes.icns")
        file.outputStream().use { stream ->
            DataOutputStream(stream).use { out ->
                out.writeBytes("icns")
                out.writeInt(8 + renamed.entries.sumOf { 8 + it.value.size })
                for ((type, payload) in renamed) {
                    out.writeBytes(type)
                    out.writeInt(8 + payload.size)
                    out.write(payload)
                }
            }
        }

        assertTrue(IcnsIcon.missingRepresentations(file).isEmpty())
    }

    @Test
    fun `a wrong declared container length reports every slot missing`() {
        // Valid entry records after a header that lies about the total length: the container is
        // corrupt, so nothing in it can be trusted as present.
        val complete = tmp.newFile("complete.icns")
        IcnsIcon.write(sourceImage(64), complete)
        val data = complete.readBytes()
        data[4] = 0
        data[5] = 0
        data[6] = 0
        data[7] = 8
        val lying = tmp.newFile("lying.icns").apply { writeBytes(data) }

        assertEquals(10, IcnsIcon.missingRepresentations(lying).size)
    }

    @Test
    fun `a file that is not an icns reports every slot missing rather than throwing`() {
        val bogus = tmp.newFile("bogus.icns").apply { writeText("not an icns") }
        assertEquals(10, IcnsIcon.missingRepresentations(bogus).size)
    }

    @Test
    fun `the bundled default mac icon is complete`() {
        // The validator runs against whatever iconFile resolves to, including our own default.
        val resource =
            javaClass.classLoader.getResourceAsStream("default-potassium-icon-mac.icns")
        assertTrue("default icns resource must exist", resource != null)
        val copy = tmp.newFile("default.icns")
        resource!!.use { copy.outputStream().use { out -> it.copyTo(out) } }
        assertEquals(emptyList<String>(), IcnsIcon.missingRepresentations(copy))
    }
}
