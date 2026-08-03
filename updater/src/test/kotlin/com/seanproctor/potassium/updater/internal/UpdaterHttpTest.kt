package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.NetworkException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdaterHttpTest {
    @Test
    fun `blockMapUrl appends to a plain url`() {
        assertEquals(
            "https://host/app-1.0.0.zip.blockmap",
            UpdaterHttp.blockMapUrl("https://host/app-1.0.0.zip"),
        )
    }

    @Test
    fun `blockMapUrl inserts before the query string`() {
        assertEquals(
            "https://cdn/app-1.0.0.zip.blockmap?X-Amz-Signature=abc",
            UpdaterHttp.blockMapUrl("https://cdn/app-1.0.0.zip?X-Amz-Signature=abc"),
        )
    }

    @Test
    fun `request carries auth and range headers`() {
        val request =
            UpdaterHttp.request(
                "https://host/file",
                mapOf("Authorization" to "token abc"),
                "bytes=0-9",
            )

        assertEquals(listOf("token abc"), request.headers().allValues("Authorization"))
        assertEquals(listOf("bytes=0-9"), request.headers().allValues("Range"))
        assertEquals(UpdaterHttp.REQUEST_TIMEOUT, request.timeout().orElseThrow())
    }

    @Test
    fun `readBounded returns full content within the limit`() {
        val data = ByteArray(10_000) { it.toByte() }

        assertArrayEquals(data, UpdaterHttp.readBounded(data.inputStream(), 10_000))
    }

    @Test
    fun `readBounded throws once the limit is exceeded`() {
        val data = ByteArray(10_000)

        assertThrows(NetworkException::class.java) {
            UpdaterHttp.readBounded(data.inputStream(), 9_999)
        }
    }
}
