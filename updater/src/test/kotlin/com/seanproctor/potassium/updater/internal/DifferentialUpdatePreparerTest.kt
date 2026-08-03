package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.UpdateFile
import com.seanproctor.potassium.updater.exception.UpdateException
import com.seanproctor.potassium.updater.provider.GenericProvider
import com.seanproctor.potassium.updater.runtime.Platform
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

class DifferentialUpdatePreparerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var handler: RangeHttpHandler
    private lateinit var exeHandler: RangeHttpHandler
    private lateinit var serverBaseUrl: String

    private val oldExeBlockMapRequests = AtomicInteger(0)
    private val newExeBlockMapRequests = AtomicInteger(0)

    // Segment-structured contents: shared segments are reused, fresh ones must be downloaded.
    private val segmentA = ByteArray(2000) { (it % 13).toByte() }
    private val segmentB = ByteArray(1500) { (it % 7).toByte() }
    private val segmentX = ByteArray(800) { (it % 3 + 100).toByte() }

    private val oldSegments = listOf(segmentA, segmentB)
    private val newSegments = listOf(segmentA, segmentX, segmentB)

    private val oldAppImage = embeddedBlockMapFile(oldSegments)
    private val newAppImage = embeddedBlockMapFile(newSegments)

    // Plain sidecar-style artifacts for the Windows seeded-installer path.
    private val oldExeBytes = segmentA + segmentB
    private val newExeBytes = segmentA + segmentX + segmentB

    @Before
    fun startServer() {
        oldExeBlockMapRequests.set(0)
        newExeBlockMapRequests.set(0)

        handler = RangeHttpHandler(newAppImage.bytes)
        exeHandler = RangeHttpHandler(newExeBytes)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app-2.0.0.AppImage", handler)
        server.createContext("/app-2.0.0.exe", exeHandler)
        server.createContext("/app-2.0.0.exe.blockmap") { exchange ->
            newExeBlockMapRequests.incrementAndGet()
            val body = BlockMapFixtures.gzip(BlockMapFixtures.blockMapJson(newSegments))
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/app-1.0.0.exe.blockmap") { exchange ->
            oldExeBlockMapRequests.incrementAndGet()
            val body = BlockMapFixtures.gzip(BlockMapFixtures.blockMapJson(oldSegments))
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        serverBaseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `modeFor selects embedded for AppImage with blockMapSize`() {
        assertEquals(
            DifferentialUpdatePreparer.Mode.EMBEDDED,
            preparer().modeFor(InstallType.APPIMAGE, updateFile("app-2.0.0.AppImage", blockMapSize = 100)),
        )
    }

    @Test
    fun `modeFor returns null for AppImage without blockMapSize`() {
        assertNull(preparer().modeFor(InstallType.APPIMAGE, updateFile("app-2.0.0.AppImage", blockMapSize = null)))
    }

    @Test
    fun `modeFor selects sidecar for zip and the nsis family`() {
        assertEquals(
            DifferentialUpdatePreparer.Mode.SIDECAR,
            preparer().modeFor(InstallType.ZIP, updateFile("app-2.0.0-mac.zip")),
        )
        assertEquals(
            DifferentialUpdatePreparer.Mode.SIDECAR,
            preparer().modeFor(InstallType.NSIS, updateFile("app-2.0.0-nsis.exe")),
        )
        assertEquals(
            DifferentialUpdatePreparer.Mode.SIDECAR,
            preparer().modeFor(InstallType.EXE, updateFile("app-2.0.0.exe")),
        )
        // nsis-web installs update via the full NSIS installer, which has a blockmap too.
        assertEquals(
            DifferentialUpdatePreparer.Mode.SIDECAR,
            preparer().modeFor(InstallType.NSIS_WEB, updateFile("app-2.0.0-nsis.exe")),
        )
    }

    @Test
    fun `modeFor returns null for unsupported types`() {
        assertNull(preparer().modeFor(InstallType.DEB, updateFile("app-2.0.0.deb")))
        assertNull(preparer().modeFor(InstallType.MSI, updateFile("app-2.0.0.msi")))
        assertNull(preparer().modeFor(null, updateFile("app-2.0.0.zip")))
        // Type and file extension must agree.
        assertNull(preparer().modeFor(InstallType.ZIP, updateFile("app-2.0.0.dmg")))
    }

    @Test
    fun `embedded prepare and download reconstruct the new AppImage`() {
        val preparer = preparer(appImagePath = oldAppImageFile().absolutePath)
        val destination = tempFolder.newFile()
        val target = appImageTarget()

        val prepared = preparer.prepare(DifferentialUpdatePreparer.Mode.EMBEDDED, target, "2.0.0", destination)
        runBlocking {
            DifferentialDownloader(HttpClient.newHttpClient(), emptyMap())
                .download(prepared.request) { _, _ -> }
        }

        assertArrayEquals(newAppImage.bytes, destination.readBytes())
        assertTrue(ChecksumVerifier.verify(destination, target.sha512))
        // Shared segments were copied locally: transferred bytes stay well below the full size.
        assertTrue(
            "served ${handler.bytesServed} of ${newAppImage.bytes.size}",
            handler.bytesServed < newAppImage.bytes.size,
        )
        assertNull(prepared.newBlockMapBytes)
    }

    @Test
    fun `embedded prepare fails without APPIMAGE env`() {
        assertThrows(UpdateException::class.java) {
            preparer(appImagePath = null)
                .prepare(DifferentialUpdatePreparer.Mode.EMBEDDED, appImageTarget(), "2.0.0", tempFolder.newFile())
        }
    }

    @Test
    fun `embedded prepare fails when manifest blockMapSize disagrees with the trailer`() {
        val preparer = preparer(appImagePath = oldAppImageFile().absolutePath)
        val target = appImageTarget(blockMapSizeOverride = newAppImage.blockMapSize - 1)

        assertThrows(UpdateException::class.java) {
            preparer.prepare(DifferentialUpdatePreparer.Mode.EMBEDDED, target, "2.0.0", tempFolder.newFile())
        }
    }

    @Test
    fun `embedded prepare rejects an absurd manifest blockMapSize before any fetch`() {
        val preparer = preparer(appImagePath = oldAppImageFile().absolutePath)
        val target = appImageTarget(blockMapSizeOverride = BlockMapCodec.MAX_BLOCKMAP_BYTES + 1)

        assertThrows(UpdateException::class.java) {
            preparer.prepare(DifferentialUpdatePreparer.Mode.EMBEDDED, target, "2.0.0", tempFolder.newFile())
        }
        assertEquals(0, handler.requests.size)
    }

    @Test
    fun `sidecar prepare fails without a cached artifact`() {
        assertThrows(UpdateException::class.java) {
            preparer().prepare(
                DifferentialUpdatePreparer.Mode.SIDECAR,
                updateFile("app-2.0.0.zip"),
                "2.0.0",
                tempFolder.newFile(),
            )
        }
        // The empty-cache check runs before any network request.
        assertEquals(0, handler.requests.size)
    }

    @Test
    fun `seeded installer enables a first-update differential on Windows`() {
        val preparer = seededPreparer()
        val destination = tempFolder.newFile()
        val target = exeTarget()

        val prepared = preparer.prepare(DifferentialUpdatePreparer.Mode.SIDECAR, target, "2.0.0", destination)
        runBlocking {
            DifferentialDownloader(HttpClient.newHttpClient(), emptyMap())
                .download(prepared.request) { _, _ -> }
        }

        assertArrayEquals(newExeBytes, destination.readBytes())
        // The old blockmap came from the server (version-substituted URL); the old bytes
        // came from the NSIS-seeded installer copy.
        assertEquals(1, oldExeBlockMapRequests.get())
        assertTrue(
            "served ${exeHandler.bytesServed} of ${newExeBytes.size}",
            exeHandler.bytesServed < newExeBytes.size,
        )
    }

    @Test
    fun `seeded path requires a versioned file name`() {
        // The seeded installer exists, but an unversioned artifact name means the old
        // blockmap URL cannot be derived — the attempt must abort (and fall back).
        val preparer = seededPreparer()

        assertThrows(UpdateException::class.java) {
            preparer.prepare(
                DifferentialUpdatePreparer.Mode.SIDECAR,
                updateFile("app.exe", size = newExeBytes.size.toLong()),
                "2.0.0",
                tempFolder.newFile(),
            )
        }
    }

    @Test
    fun `seeded installer is ignored off Windows`() {
        val preparer = seededPreparer(platform = Platform.Linux)

        assertThrows(UpdateException::class.java) {
            preparer.prepare(DifferentialUpdatePreparer.Mode.SIDECAR, exeTarget(), "2.0.0", tempFolder.newFile())
        }
        assertEquals(0, newExeBlockMapRequests.get())
    }

    @Test
    fun `missing seeded installer file fails before any network request`() {
        val preparer = seededPreparer(writeInstaller = false)

        assertThrows(UpdateException::class.java) {
            preparer.prepare(DifferentialUpdatePreparer.Mode.SIDECAR, exeTarget(), "2.0.0", tempFolder.newFile())
        }
        assertEquals(0, newExeBlockMapRequests.get())
    }

    private fun preparer(appImagePath: String? = null): DifferentialUpdatePreparer =
        DifferentialUpdatePreparer(
            httpClient = HttpClient.newHttpClient(),
            provider = GenericProvider(serverBaseUrl),
            cache = UpdateCache(tempFolder.newFolder()),
            currentVersion = "1.0.0",
            env = FakeEnv(if (appImagePath == null) emptyMap() else mapOf("APPIMAGE" to appImagePath)),
        )

    /**
     * A preparer whose environment mimics a Windows machine right after an NSIS install:
     * `resources/updater-cache-dir` names the seed directory and
     * `%LOCALAPPDATA%\<name>\installer.exe` holds the previous installer's bytes.
     */
    private fun seededPreparer(
        platform: Platform = Platform.Windows,
        writeInstaller: Boolean = true,
    ): DifferentialUpdatePreparer {
        val appRoot = tempFolder.newFolder()
        File(appRoot, "resources").mkdirs()
        File(appRoot, "resources/updater-cache-dir").writeText("myapp-updater\n")

        val localAppData = tempFolder.newFolder()
        if (writeInstaller) {
            val seedDir = File(localAppData, "myapp-updater")
            seedDir.mkdirs()
            File(seedDir, "installer.exe").writeBytes(oldExeBytes)
        }

        return DifferentialUpdatePreparer(
            httpClient = HttpClient.newHttpClient(),
            provider = GenericProvider(serverBaseUrl),
            cache = UpdateCache(tempFolder.newFolder()),
            currentVersion = "1.0.0",
            env =
                FakeEnv(
                    envVars = mapOf("LOCALAPPDATA" to localAppData.absolutePath),
                    platform = platform,
                    systemProps = mapOf("java.home" to File(appRoot, "runtime").absolutePath),
                ),
        )
    }

    private fun oldAppImageFile(): File {
        val file = tempFolder.newFile("current.AppImage")
        file.writeBytes(oldAppImage.bytes)
        return file
    }

    private fun appImageTarget(blockMapSizeOverride: Long? = null): UpdateFile =
        updateFile(
            fileName = "app-2.0.0.AppImage",
            blockMapSize = blockMapSizeOverride ?: newAppImage.blockMapSize,
            size = newAppImage.bytes.size.toLong(),
            sha512 = BlockMapFixtures.base64Sha512(newAppImage.bytes),
        )

    private fun exeTarget(): UpdateFile =
        updateFile(
            fileName = "app-2.0.0.exe",
            size = newExeBytes.size.toLong(),
            sha512 = BlockMapFixtures.base64Sha512(newExeBytes),
        )

    private fun updateFile(
        fileName: String,
        blockMapSize: Long? = null,
        size: Long = 0,
        sha512: String = "",
    ): UpdateFile =
        UpdateFile(
            url = "$serverBaseUrl/$fileName",
            sha512 = sha512,
            size = size,
            blockMapSize = blockMapSize,
            fileName = fileName,
        )

    private class EmbeddedFile(
        val bytes: ByteArray,
        val blockMapSize: Long,
    )

    /** Builds `[segments][deflateRaw(blockmap json)][uint32 BE length]` like electron-builder. */
    private fun embeddedBlockMapFile(segments: List<ByteArray>): EmbeddedFile {
        val content = segments.reduce(ByteArray::plus)
        val tail = BlockMapFixtures.embeddedTail(BlockMapFixtures.blockMapJson(segments))
        return EmbeddedFile(content + tail, (tail.size - BlockMapCodec.TRAILER_LENGTH).toLong())
    }

    private class FakeEnv(
        private val envVars: Map<String, String>,
        override val platform: Platform = Platform.Linux,
        private val systemProps: Map<String, String> = emptyMap(),
    ) : InstallEnvironment {
        override fun getenv(name: String): String? = envVars[name]

        override fun systemProperty(name: String): String? = systemProps[name]

        override fun fileExists(path: String): Boolean = File(path).isFile

        override fun readText(path: String): String? = File(path).takeIf { it.isFile }?.readText()

        override fun executablePath(): String? = null
    }
}
