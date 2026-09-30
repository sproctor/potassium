package com.seanproctor.potassium.updater.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GenericProviderTest {
    @Test
    fun `https base urls are accepted`() {
        val provider = GenericProvider("https://updates.example.com/releases/")

        assertEquals(
            "https://updates.example.com/releases/app-1.0.0.zip",
            provider.getDownloadUrl("app-1.0.0.zip", "1.0.0"),
        )
    }

    @Test
    fun `plain http is rejected for remote hosts`() {
        for (url in listOf("http://updates.example.com", "HTTP://updates.example.com", "http://10.0.0.5:8080")) {
            assertThrows(url, IllegalArgumentException::class.java) { GenericProvider(url) }
        }
    }

    @Test
    fun `plain http is allowed for loopback hosts`() {
        for (url in listOf("http://localhost:8080", "http://127.0.0.1:1234/x", "http://127.1.2.3", "http://[::1]:80")) {
            GenericProvider(url)
        }
    }

    @Test
    fun `other schemes and malformed urls are rejected`() {
        for (url in listOf(
            "ftp://updates.example.com",
            "file:///tmp/updates",
            "updates.example.com",
            "https://bad host",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) { GenericProvider(url) }
        }
    }
}
