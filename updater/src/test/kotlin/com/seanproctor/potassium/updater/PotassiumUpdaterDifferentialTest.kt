package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.internal.BlockMapFixtures
import com.seanproctor.potassium.updater.internal.RangeHttpHandler
import com.seanproctor.potassium.updater.internal.UpdateCache
import com.seanproctor.potassium.updater.provider.GenericProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end tests of the differential download path through the public API, against a
 * local HTTP server hosting a `latest-*.yml` manifest, the update artifact (with Range
 * support), and gzip sidecar blockmaps.
 */
class PotassiumUpdaterDifferentialTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var zipHandler: RangeHttpHandler
    private lateinit var serverBaseUrl: String
    private lateinit var originalTmpDir: String

    private val oldBlockMapRequests = AtomicInteger(0)
    private val newBlockMapRequests = AtomicInteger(0)

    @Volatile
    private var newBlockMapStatus = 200

    // Segment-structured artifacts: A and B are shared, X is new in 2.0.0.
    private val segmentA = ByteArray(4000) { (it % 13).toByte() }
    private val segmentB = ByteArray(3000) { (it % 7).toByte() }
    private val segmentX = ByteArray(1200) { (it % 5 + 50).toByte() }

    private val oldBytes = segmentA + segmentB
    private val newBytes = segmentA + segmentX + segmentB

    private val oldBlockMapGzip = BlockMapFixtures.gzip(BlockMapFixtures.blockMapJson(listOf(segmentA, segmentB)))
    private val newBlockMapGzip =
        BlockMapFixtures.gzip(BlockMapFixtures.blockMapJson(listOf(segmentA, segmentX, segmentB)))

    private val manifest: String
        get() =
            """
            version: 2.0.0
            files:
              - url: app-2.0.0.zip
                sha512: ${BlockMapFixtures.base64Sha512(newBytes)}
                size: ${newBytes.size}
            releaseDate: '2026-08-01T10:00:00.000Z'
            """.trimIndent()

    @Before
    fun startServer() {
        // Isolate downloads from the machine-global tmpdir: parallel test JVMs would race
        // on the fixed artifact names the updater writes there.
        originalTmpDir = System.getProperty("java.io.tmpdir")
        System.setProperty("java.io.tmpdir", tempFolder.newFolder("downloads").absolutePath)

        oldBlockMapRequests.set(0)
        newBlockMapRequests.set(0)
        newBlockMapStatus = 200

        zipHandler = RangeHttpHandler(newBytes)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app-2.0.0.zip", zipHandler)
        server.createContext("/app-2.0.0.zip.blockmap") { exchange ->
            newBlockMapRequests.incrementAndGet()
            respond(exchange, newBlockMapStatus, newBlockMapGzip)
        }
        server.createContext("/app-1.0.0.zip.blockmap") { exchange ->
            oldBlockMapRequests.incrementAndGet()
            respond(exchange, 200, oldBlockMapGzip)
        }
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path.endsWith(".yml")) {
                respond(exchange, 200, manifest.toByteArray())
            } else {
                respond(exchange, 404, ByteArray(0))
            }
        }
        server.start()
        serverBaseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
        System.setProperty("java.io.tmpdir", originalTmpDir)
    }

    @Test
    fun `differential download transfers only changed bytes and refreshes the cache`() {
        val cacheDir = seededCacheDir(withBlockMap = true)

        val downloaded = download(newUpdater(cacheDir))

        assertArrayEquals(newBytes, downloaded.readBytes())
        assertTrue(
            "served ${zipHandler.bytesServed} of ${newBytes.size}",
            zipHandler.bytesServed < newBytes.size,
        )
        assertTrue(zipHandler.rangeRequests.isNotEmpty())
        // The old blockmap came from the cache, not the server.
        assertEquals(0, oldBlockMapRequests.get())

        // The cache now holds the 2.0.0 artifact and its blockmap for the next update.
        val entry = UpdateCache(cacheDir).read()
        assertNotNull(entry)
        assertEquals("2.0.0", entry!!.version)
        assertArrayEquals(newBytes, entry.artifact.readBytes())
        assertNotNull(UpdateCache(cacheDir).readBlockMap())
    }

    @Test
    fun `first update without a cache falls back to a full download and seeds the cache`() {
        val cacheDir = tempFolder.newFolder()

        val downloaded = download(newUpdater(cacheDir))

        assertArrayEquals(newBytes, downloaded.readBytes())
        // Only a plain full GET was made for the artifact.
        assertEquals(0, zipHandler.rangeRequests.size)
        assertEquals(1, zipHandler.requests.size)
        // The cache was seeded (including a best-effort blockmap fetch) for the next update.
        assertEquals("2.0.0", UpdateCache(cacheDir).read()?.version)
        assertNotNull(UpdateCache(cacheDir).readBlockMap())
    }

    @Test
    fun `missing new blockmap falls back to a full download`() {
        newBlockMapStatus = 404
        val cacheDir = seededCacheDir(withBlockMap = true)

        val downloaded = download(newUpdater(cacheDir))

        assertArrayEquals(newBytes, downloaded.readBytes())
        assertEquals(0, zipHandler.rangeRequests.size)
    }

    @Test
    fun `server without range support falls back to a full download`() {
        zipHandler.ignoreRange = true
        val cacheDir = seededCacheDir(withBlockMap = true)

        val downloaded = download(newUpdater(cacheDir))

        assertArrayEquals(newBytes, downloaded.readBytes())
    }

    @Test
    fun `corrupted range data fails the checksum and falls back to a full download`() {
        zipHandler.corruptRanges = true
        val cacheDir = seededCacheDir(withBlockMap = true)

        val downloaded = download(newUpdater(cacheDir))

        assertArrayEquals(newBytes, downloaded.readBytes())
        // The differential attempt did issue ranges before the checksum caught the corruption.
        assertTrue(zipHandler.rangeRequests.isNotEmpty())
    }

    @Test
    fun `old blockmap is fetched from the server when not cached`() {
        val cacheDir = seededCacheDir(withBlockMap = false)

        val downloaded = download(newUpdater(cacheDir))

        assertArrayEquals(newBytes, downloaded.readBytes())
        assertEquals(1, oldBlockMapRequests.get())
        assertTrue(zipHandler.bytesServed < newBytes.size)
    }

    @Test
    fun `unversioned artifact names still update correctly via fallback`() {
        // Cached under the same file name the new release uses: the old-blockmap URL would
        // equal the new one, so the differential path aborts and the full download runs.
        val cacheDir = tempFolder.newFolder()
        val oldArtifact = tempFolder.newFile()
        oldArtifact.writeBytes(oldBytes)
        UpdateCache(cacheDir).store(
            oldArtifact,
            null,
            "1.0.0",
            "app-2.0.0.zip",
            BlockMapFixtures.base64Sha512(oldBytes),
        )

        val downloaded = download(newUpdater(cacheDir))

        assertArrayEquals(newBytes, downloaded.readBytes())
        assertEquals(0, oldBlockMapRequests.get())
    }

    @Test
    fun `disableDifferentialDownload skips blockmaps and clears the cache`() {
        val cacheDir = seededCacheDir(withBlockMap = true)

        val downloaded =
            download(newUpdater(cacheDir) { disableDifferentialDownload = true })

        assertArrayEquals(newBytes, downloaded.readBytes())
        assertEquals(0, newBlockMapRequests.get())
        assertEquals(0, zipHandler.rangeRequests.size)
        // Disabling also reclaims the previously stored generation.
        assertNull(UpdateCache(cacheDir).read())
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `progress during a differential download tracks planned transfer size`() {
        val cacheDir = seededCacheDir(withBlockMap = true)
        val updater = newUpdater(cacheDir)

        val progress =
            runBlocking {
                val info = (updater.checkForUpdates() as UpdateResult.Available).info
                updater.downloadUpdate(info).toList()
            }

        val file = progress.last().file
        assertNotNull(file)
        // Intermediate emissions report against the planned download size, not the full size.
        val intermediate = progress.dropLast(1)
        assertTrue(intermediate.isNotEmpty())
        assertEquals(segmentX.size.toLong(), intermediate.last().totalBytes)
        assertEquals(segmentX.size.toLong(), intermediate.last().bytesDownloaded)
    }

    private fun newUpdater(
        cacheDir: File,
        configure: UpdaterConfig.() -> Unit = {},
    ): PotassiumUpdater =
        PotassiumUpdater {
            currentVersion = "1.0.0"
            provider = GenericProvider(serverBaseUrl)
            executableType = InstallType.ZIP
            updateCacheDir = cacheDir
            configure()
        }

    private fun download(updater: PotassiumUpdater): File =
        runBlocking {
            val result = updater.checkForUpdates()
            assertTrue("expected an available update, got $result", result is UpdateResult.Available)
            val info = (result as UpdateResult.Available).info
            requireNotNull(
                updater
                    .downloadUpdate(info)
                    .toList()
                    .last()
                    .file,
            )
        }

    private fun seededCacheDir(withBlockMap: Boolean): File {
        val cacheDir = tempFolder.newFolder()
        val oldArtifact = tempFolder.newFile()
        oldArtifact.writeBytes(oldBytes)
        UpdateCache(cacheDir).store(
            artifact = oldArtifact,
            blockMapBytes = if (withBlockMap) oldBlockMapGzip else null,
            version = "1.0.0",
            fileName = "app-1.0.0.zip",
            sha512 = BlockMapFixtures.base64Sha512(oldBytes),
        )
        return cacheDir
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: ByteArray,
    ) {
        if (body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }
}
