package com.seanproctor.potassium.updater.provider

import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.runtime.Platform
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GitHubProviderTest {
    private lateinit var server: HttpServer
    private lateinit var httpClient: HttpClient
    private lateinit var serverBaseUrl: String
    private val feedCallCount = AtomicInteger(0)
    private val lastAuthHeader = AtomicReference<String?>(null)
    private var feedBody: String = ""
    private var feedStatus: Int = HTTP_OK

    @Before
    fun startServer() {
        feedCallCount.set(0)
        lastAuthHeader.set(null)
        feedBody = atomFeed()
        feedStatus = HTTP_OK

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/acme/tool/releases.atom",
            HttpHandler { exchange: HttpExchange ->
                feedCallCount.incrementAndGet()
                lastAuthHeader.set(exchange.requestHeaders.getFirst("Authorization"))
                val bytes = feedBody.toByteArray()
                exchange.sendResponseHeaders(feedStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            },
        )
        server.start()
        serverBaseUrl = "http://127.0.0.1:${server.address.port}"
        httpClient = HttpClient.newHttpClient()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun newProvider(token: String? = null): GitHubProvider =
        GitHubProvider("acme", "tool", token).apply { baseUrl = serverBaseUrl }

    @Test
    fun `default base url is github`() {
        assertEquals(
            "https://github.com/acme/tool/releases/latest/download/latest.yml",
            GitHubProvider("acme", "tool").getUpdateMetadataUrl("latest", Platform.Windows),
        )
    }

    @Test
    fun `stable channel makes no feed call`() {
        val url = newProvider().resolveMetadataUrl("latest", Platform.Linux, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/latest/download/latest-linux.yml", url)
        assertEquals(0, feedCallCount.get())
    }

    @Test
    fun `beta channel finds the newest matching pre-release from the feed`() {
        feedBody = atomFeed("v1.2.3-alpha.4", "v1.2.3-beta.5", "v1.2.2")
        val url = newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/download/v1.2.3-beta.5/beta-linux.yml", url)
        assertEquals(1, feedCallCount.get())
    }

    @Test
    fun `beta channel picks the first match in feed order`() {
        feedBody = atomFeed("v1.3.0-beta.2", "v1.3.0-beta.1", "v1.2.9-beta.7")
        val url = newProvider().resolveMetadataUrl("beta", Platform.Windows, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/download/v1.3.0-beta.2/beta.yml", url)
    }

    @Test
    fun `beta channel skips alpha and same-prefix channels`() {
        // alpha.* (channel "alpha") and beta-leftover (channel "beta-leftover") must NOT match "beta".
        feedBody = atomFeed("v1.0.0-alpha.9", "v1.0.0-beta-leftover", "v1.0.0-beta.3")
        val url = newProvider().resolveMetadataUrl("beta", Platform.MacOS, httpClient)
        assertEquals("$serverBaseUrl/acme/tool/releases/download/v1.0.0-beta.3/beta-mac.yml", url)
    }

    @Test
    fun `throws when no release matches the channel`() {
        feedBody = atomFeed("v1.0.0-alpha.1", "v1.0.0")
        try {
            newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
            fail("expected NoSuchElementException")
        } catch (e: NoSuchElementException) {
            assertNotNull(e.message)
            assertTrue("message should name the channel", e.message!!.contains("No release found for channel 'beta'"))
        }
    }

    @Test
    fun `throws on feed http error`() {
        feedStatus = 503
        feedBody = "unavailable"
        try {
            newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
            fail("expected NetworkException")
        } catch (e: NetworkException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `auth header sent when token configured`() {
        feedBody = atomFeed("v1.0.0-beta.1")
        newProvider(token = "ghp_test").resolveMetadataUrl("beta", Platform.Linux, httpClient)
        assertEquals("Bearer ghp_test", lastAuthHeader.get())
    }

    @Test
    fun `no auth header when token is null`() {
        feedBody = atomFeed("v1.0.0-beta.1")
        newProvider().resolveMetadataUrl("beta", Platform.Linux, httpClient)
        assertNull(lastAuthHeader.get())
    }

    private fun atomFeed(vararg tags: String): String =
        buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<feed xmlns="http://www.w3.org/2005/Atom">""")
            append("""<link rel="self" href="https://github.com/acme/tool/releases.atom"/>""")
            for (tag in tags) {
                append("<entry>")
                append("""<link rel="alternate" type="text/html" href="https://github.com/acme/tool/releases/tag/$tag"/>""")
                append("</entry>")
            }
            append("</feed>")
        }

    private companion object {
        const val HTTP_OK = 200
    }
}
