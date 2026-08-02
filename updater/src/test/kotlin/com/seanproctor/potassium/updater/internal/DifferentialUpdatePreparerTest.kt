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

class DifferentialUpdatePreparerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var handler: RangeHttpHandler
    private lateinit var serverBaseUrl: String

    // Segment-structured contents: shared segments are reused, fresh ones must be downloaded.
    private val segmentA = ByteArray(2000) { (it % 13).toByte() }
    private val segmentB = ByteArray(1500) { (it % 7).toByte() }
    private val segmentX = ByteArray(800) { (it % 3 + 100).toByte() }

    private val oldSegments = listOf(segmentA, segmentB)
    private val newSegments = listOf(segmentA, segmentX, segmentB)

    private val oldAppImage = embeddedBlockMapFile(oldSegments)
    private val newAppImage = embeddedBlockMapFile(newSegments)

    @Before
    fun startServer() {
        handler = RangeHttpHandler(newAppImage.bytes)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app-2.0.0.AppImage", handler)
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

        val prepared = preparer.prepare(DifferentialUpdatePreparer.Mode.EMBEDDED, target, destination)
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
                .prepare(DifferentialUpdatePreparer.Mode.EMBEDDED, appImageTarget(), tempFolder.newFile())
        }
    }

    @Test
    fun `embedded prepare fails when manifest blockMapSize disagrees with the trailer`() {
        val preparer = preparer(appImagePath = oldAppImageFile().absolutePath)
        val target = appImageTarget(blockMapSizeOverride = newAppImage.blockMapSize - 1)

        assertThrows(UpdateException::class.java) {
            preparer.prepare(DifferentialUpdatePreparer.Mode.EMBEDDED, target, tempFolder.newFile())
        }
    }

    @Test
    fun `embedded prepare rejects an absurd manifest blockMapSize before any fetch`() {
        val preparer = preparer(appImagePath = oldAppImageFile().absolutePath)
        val target = appImageTarget(blockMapSizeOverride = BlockMapCodec.MAX_BLOCKMAP_BYTES + 1)

        assertThrows(UpdateException::class.java) {
            preparer.prepare(DifferentialUpdatePreparer.Mode.EMBEDDED, target, tempFolder.newFile())
        }
        assertEquals(0, handler.requests.size)
    }

    @Test
    fun `sidecar prepare fails without a cached artifact`() {
        assertThrows(UpdateException::class.java) {
            preparer().prepare(
                DifferentialUpdatePreparer.Mode.SIDECAR,
                updateFile("app-2.0.0.zip"),
                tempFolder.newFile(),
            )
        }
        // The empty-cache check runs before any network request.
        assertEquals(0, handler.requests.size)
    }

    private fun preparer(appImagePath: String? = null): DifferentialUpdatePreparer =
        DifferentialUpdatePreparer(
            httpClient = HttpClient.newHttpClient(),
            provider = GenericProvider(serverBaseUrl),
            cache = UpdateCache(tempFolder.newFolder()),
            env = FakeEnv(if (appImagePath == null) emptyMap() else mapOf("APPIMAGE" to appImagePath)),
        )

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
    ) : InstallEnvironment {
        override val platform: Platform get() = Platform.Linux

        override fun getenv(name: String): String? = envVars[name]

        override fun systemProperty(name: String): String? = null

        override fun fileExists(path: String): Boolean = File(path).isFile

        override fun readText(path: String): String? = null

        override fun executablePath(): String? = null
    }
}
