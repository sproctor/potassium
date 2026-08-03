package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.exception.UpdateException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger

class DifferentialDownloaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var handler: RangeHttpHandler
    private lateinit var artifactUrl: String
    private val redirectCount = AtomicInteger(0)

    private val oldBytes = ByteArray(100) { it.toByte() }

    // New file reuses old[0, 40) and old[60, 100) with 30 fresh bytes in between.
    private val newBytes =
        oldBytes.copyOfRange(0, 40) +
            ByteArray(30) { (200 + it).toByte() } +
            oldBytes.copyOfRange(60, 100)

    private val plan =
        DownloadPlan(
            operations =
                listOf(
                    PlanOperation.Copy(0, 40),
                    PlanOperation.Download(40, 70),
                    PlanOperation.Copy(60, 100),
                ),
            downloadSize = 30,
            copySize = 80,
        )

    @Before
    fun startServer() {
        redirectCount.set(0)
        handler = RangeHttpHandler(newBytes)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/artifact.zip", handler)
        server.createContext("/redirect.zip") { exchange ->
            redirectCount.incrementAndGet()
            exchange.responseHeaders.set("Location", "http://localhost:${server.address.port}/artifact.zip")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        artifactUrl = "http://127.0.0.1:${server.address.port}/artifact.zip"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `assembles new file from copies and ranged downloads`() {
        val destination = tempFolder.newFile()

        runBlocking {
            newDownloader().download(request(destination)) { _, _ -> }
        }

        assertArrayEquals(newBytes, destination.readBytes())
        assertEquals(listOf("bytes=40-69"), handler.rangeRequests.map { it.range })
        assertEquals(30L, handler.bytesServed)
    }

    @Test
    fun `appends trailer verbatim`() {
        val destination = tempFolder.newFile()
        val trailer = byteArrayOf(9, 8, 7, 6)

        runBlocking {
            newDownloader().download(request(destination, trailer = trailer)) { _, _ -> }
        }

        assertArrayEquals(newBytes + trailer, destination.readBytes())
    }

    @Test
    fun `throws when the server ignores the range header`() {
        handler.ignoreRange = true
        val destination = tempFolder.newFile()

        assertThrows(NetworkException::class.java) {
            runBlocking { newDownloader().download(request(destination)) { _, _ -> } }
        }
    }

    @Test
    fun `throws on a truncated range response`() {
        handler.truncateRanges = true
        val destination = tempFolder.newFile()

        assertThrows(NetworkException::class.java) {
            runBlocking { newDownloader().download(request(destination)) { _, _ -> } }
        }
    }

    @Test
    fun `throws when a copy range exceeds the old file`() {
        val destination = tempFolder.newFile()
        val badPlan =
            DownloadPlan(
                operations = listOf(PlanOperation.Copy(0, 200)),
                downloadSize = 0,
                copySize = 200,
            )

        assertThrows(UpdateException::class.java) {
            runBlocking {
                newDownloader().download(request(destination, plan = badPlan)) { _, _ -> }
            }
        }
    }

    @Test
    fun `reports progress against the planned download size`() {
        val destination = tempFolder.newFile()
        val progress = mutableListOf<Pair<Long, Long>>()

        runBlocking {
            newDownloader().download(request(destination)) { downloaded, total ->
                progress.add(downloaded to total)
            }
        }

        assertTrue(progress.isNotEmpty())
        assertEquals(30L to 30L, progress.last())
        assertTrue(progress.map { it.first } == progress.map { it.first }.sorted())
    }

    @Test
    fun `sends auth headers to the original host`() {
        val destination = tempFolder.newFile()

        runBlocking {
            newDownloader(mapOf("Authorization" to "token abc"))
                .download(request(destination)) { _, _ -> }
        }

        assertEquals(listOf("token abc"), handler.rangeRequests.map { it.authorization })
    }

    @Test
    fun `reuses the redirected uri and drops auth for the new host`() {
        val destination = tempFolder.newFile()
        val twoRangePlan =
            DownloadPlan(
                operations =
                    listOf(
                        PlanOperation.Download(0, 40),
                        PlanOperation.Copy(0, 40),
                        PlanOperation.Download(40, 110),
                    ),
                downloadSize = 110,
                copySize = 40,
            )
        // Plan output: new[0,40) + old[0,40) + new[40,110) — old[0,40) equals new[0,40).
        val expected = newBytes.copyOfRange(0, 40) + oldBytes.copyOfRange(0, 40) + newBytes.copyOfRange(40, 110)

        runBlocking {
            newDownloader(mapOf("Authorization" to "token abc"))
                .download(
                    request(
                        destination,
                        plan = twoRangePlan,
                        url = "http://127.0.0.1:${server.address.port}/redirect.zip",
                    ),
                ) { _, _ -> }
        }

        assertArrayEquals(expected, destination.readBytes())
        // Only the first request goes through the redirect; later ranges hit the final URI directly.
        assertEquals(1, redirectCount.get())
        assertEquals(2, handler.rangeRequests.size)
        // The second range request went directly to the redirected host (localhost != 127.0.0.1),
        // so the original host's auth header must not be forwarded.
        assertNull(handler.rangeRequests.last().authorization)
    }

    private fun newDownloader(authHeaders: Map<String, String> = emptyMap()): DifferentialDownloader =
        DifferentialDownloader(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
            authHeaders,
        )

    private fun request(
        destination: File,
        plan: DownloadPlan = this.plan,
        trailer: ByteArray? = null,
        url: String = artifactUrl,
    ): DifferentialRequest {
        val oldFile = tempFolder.newFile()
        oldFile.writeBytes(oldBytes)
        return DifferentialRequest(
            url = url,
            plan = plan,
            oldFile = oldFile,
            destination = destination,
            trailer = trailer,
        )
    }
}
