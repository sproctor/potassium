package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.ParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BlockMapTest {
    @Test
    fun `parses real electron-builder blockmap format`() {
        val json =
            """
            {"version":"2","files":[{"name":"file","offset":0,
            "checksums":["mkiqM3T7RSbBno0Ceucnkg2fGCbg","zRfMVXJyafFsHLXX1RyCBrCV3jXo"],
            "sizes":[16384,12345]}]}
            """.trimIndent()

        val blockMap = BlockMap.parse(json)

        assertEquals("2", blockMap.version)
        assertEquals("file", blockMap.file.name)
        assertEquals(0L, blockMap.file.offset)
        assertEquals(listOf(16384L, 12345L), blockMap.file.sizes)
        assertEquals(2, blockMap.file.checksums.size)
    }

    @Test
    fun `ignores unknown fields`() {
        val json =
            """{"version":"2","future":true,""" +
                """"files":[{"name":"file","offset":0,"checksums":["a"],"sizes":[8],"extra":1}]}"""

        val blockMap = BlockMap.parse(json)

        assertEquals(listOf(8L), blockMap.file.sizes)
    }

    @Test
    fun `blocks flattens sizes to absolute offsets`() {
        val json =
            """{"version":"2",""" +
                """"files":[{"name":"file","offset":100,"checksums":["a","b","c"],"sizes":[10,20,30]}]}"""

        val blocks = BlockMap.parse(json).blocks()

        assertEquals(
            listOf(
                Block("a", 100, 10),
                Block("b", 110, 20),
                Block("c", 130, 30),
            ),
            blocks,
        )
    }

    @Test
    fun `rejects empty files array`() {
        assertThrows(ParseException::class.java) {
            BlockMap.parse("""{"version":"2","files":[]}""")
        }
    }

    @Test
    fun `rejects multiple file entries`() {
        val json =
            """
            {"version":"2","files":[
            {"name":"a","offset":0,"checksums":["x"],"sizes":[1]},
            {"name":"b","offset":1,"checksums":["y"],"sizes":[1]}]}
            """.trimIndent()

        assertThrows(ParseException::class.java) { BlockMap.parse(json) }
    }

    @Test
    fun `rejects mismatched checksum and size counts`() {
        assertThrows(ParseException::class.java) {
            BlockMap.parse("""{"version":"2","files":[{"name":"file","offset":0,"checksums":["a","b"],"sizes":[1]}]}""")
        }
    }

    @Test
    fun `rejects non-positive block size`() {
        assertThrows(ParseException::class.java) {
            BlockMap.parse("""{"version":"2","files":[{"name":"file","offset":0,"checksums":["a"],"sizes":[0]}]}""")
        }
    }

    @Test
    fun `rejects malformed JSON`() {
        assertThrows(ParseException::class.java) { BlockMap.parse("{not json") }
        assertThrows(ParseException::class.java) { BlockMap.parse("""{"files":"nope"}""") }
    }
}
