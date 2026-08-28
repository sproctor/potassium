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

    @Test
    fun `write produces a complete icon set with PNG payloads at the right sizes`() {
        val target = tmp.newFile("icon.icns")
        IcnsIcon.write(sourceImage(), target)

        val expectedPixels =
            mapOf(
                "icp4" to 16,
                "ic11" to 32,
                "icp5" to 32,
                "ic12" to 64,
                "ic07" to 128,
                "ic13" to 256,
                "ic08" to 256,
                "ic14" to 512,
                "ic09" to 512,
                "ic10" to 1024,
            )
        val written = entries(target)
        assertEquals(expectedPixels.keys, written.keys)
        for ((type, payload) in written) {
            val image = ImageIO.read(payload.inputStream())
            assertTrue("$type payload must decode as an image", image != null)
            assertEquals("$type width", expectedPixels.getValue(type), image.width)
            assertEquals("$type height", expectedPixels.getValue(type), image.height)
        }
        assertTrue(IcnsIcon.missingRepresentations(target).isEmpty())
    }

    @Test
    fun `missingRepresentations reports the slots an incomplete file lacks`() {
        // The shape that shipped in the wild: retina and large sizes present, no plain 16/32.
        val complete = tmp.newFile("complete.icns")
        IcnsIcon.write(sourceImage(64), complete)
        val kept = entries(complete).filterKeys { it != "icp4" && it != "icp5" }

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
        // iconutil emits ARGB ic04/ic05 rather than PNG icp4/icp5 for the 1x small sizes; both
        // must count, or every iconutil-produced icns would warn.
        val complete = tmp.newFile("complete.icns")
        IcnsIcon.write(sourceImage(64), complete)
        val renamed =
            entries(complete).mapKeys { (type, _) ->
                when (type) {
                    "icp4" -> "ic04"
                    "icp5" -> "ic05"
                    else -> type
                }
            }

        val file = tmp.newFile("iconutil-style.icns")
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
