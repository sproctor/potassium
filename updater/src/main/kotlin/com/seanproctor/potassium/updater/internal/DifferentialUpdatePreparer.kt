package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.UpdateFile
import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.exception.UpdateException
import com.seanproctor.potassium.updater.provider.UpdateProvider
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer

/**
 * Decides whether a differential download is possible for an update and, when it is,
 * gathers everything the [DifferentialDownloader] needs: the old local file, both
 * blockmaps, and the assembled [DownloadPlan]. Every failure throws — the caller treats
 * any exception as "fall back to a full download".
 */
internal class DifferentialUpdatePreparer(
    private val httpClient: HttpClient,
    private val provider: UpdateProvider,
    private val cache: UpdateCache,
    private val env: InstallEnvironment = SystemInstallEnvironment,
) {
    enum class Mode {
        /** `.blockmap` published next to the artifact; old file comes from [UpdateCache]. */
        SIDECAR,

        /** Blockmap embedded in the artifact tail (AppImage); old file is the running AppImage. */
        EMBEDDED,
    }

    class Prepared(
        val request: DifferentialRequest,
        /** Raw gzip bytes of the new sidecar blockmap, kept for caching after a successful update. */
        val newBlockMapBytes: ByteArray?,
    )

    /** The differential mode applicable to this install, or null when only a full download works. */
    fun modeFor(
        installType: InstallType?,
        target: UpdateFile,
    ): Mode? {
        val fileName = target.fileName.lowercase()
        return when {
            installType == InstallType.APPIMAGE &&
                fileName.endsWith(".appimage") &&
                target.blockMapSize != null -> Mode.EMBEDDED

            installType == InstallType.ZIP && fileName.endsWith(".zip") -> Mode.SIDECAR

            (installType == InstallType.NSIS || installType == InstallType.EXE) &&
                fileName.endsWith(".exe") -> Mode.SIDECAR

            else -> null
        }
    }

    fun prepare(
        mode: Mode,
        target: UpdateFile,
        destination: File,
    ): Prepared =
        when (mode) {
            Mode.EMBEDDED -> prepareEmbedded(target, destination)
            Mode.SIDECAR -> prepareSidecar(target, destination)
        }

    private fun prepareEmbedded(
        target: UpdateFile,
        destination: File,
    ): Prepared {
        val blockMapSize =
            target.blockMapSize
                ?: throw UpdateException("Manifest has no blockMapSize for ${target.fileName}")
        val oldFile = resolveRunningAppImage()
        val oldBlockMap = BlockMapCodec.readEmbedded(oldFile)

        val trailerLength = blockMapSize + BlockMapCodec.TRAILER_LENGTH
        // The whole tail (compressed blockmap + uint32 trailer) is kept and later appended
        // verbatim to the reconstructed file, exactly as electron-updater does.
        val trailer = fetchNewBlockMapTrailer(target, blockMapSize, trailerLength)
        val newBlockMap = BlockMapCodec.decodeDeflateRaw(trailer.copyOf(trailer.size - BlockMapCodec.TRAILER_LENGTH))

        val plan = DownloadPlanBuilder.build(oldBlockMap, newBlockMap)
        checkSizeInvariant(plan, trailerLength, target)
        return Prepared(DifferentialRequest(target.url, plan, oldFile, destination, trailer), newBlockMapBytes = null)
    }

    private fun resolveRunningAppImage(): File =
        env
            .getenv("APPIMAGE")
            ?.let(::File)
            ?.takeIf { it.isFile && it.length() > 0 }
            ?: throw UpdateException("APPIMAGE environment variable does not point to the running AppImage")

    private fun fetchNewBlockMapTrailer(
        target: UpdateFile,
        blockMapSize: Long,
        trailerLength: Long,
    ): ByteArray {
        val trailerStart = target.size - trailerLength
        if (trailerStart <= 0) {
            throw UpdateException("Invalid blockMapSize $blockMapSize for artifact size ${target.size}")
        }
        val trailer = getRange(target.url, trailerStart, target.size - 1)
        trailerError(trailer, blockMapSize, trailerLength)?.let { throw UpdateException(it) }
        return trailer
    }

    private fun trailerError(
        trailer: ByteArray,
        blockMapSize: Long,
        trailerLength: Long,
    ): String? {
        if (trailer.size.toLong() != trailerLength) {
            return "Blockmap trailer request returned ${trailer.size} bytes, expected $trailerLength"
        }
        val declaredSize =
            ByteBuffer
                .wrap(trailer, trailer.size - BlockMapCodec.TRAILER_LENGTH, BlockMapCodec.TRAILER_LENGTH)
                .int
                .toLong() and UINT_MASK
        if (declaredSize != blockMapSize) {
            return "Embedded blockmap declares $declaredSize bytes, manifest says $blockMapSize"
        }
        return null
    }

    private fun prepareSidecar(
        target: UpdateFile,
        destination: File,
    ): Prepared {
        val cached =
            cache.read()
                ?: throw UpdateException("No previously downloaded artifact to diff against")
        val newBlockMapBytes = getBytes("${target.url}$BLOCKMAP_SUFFIX")
        val newBlockMap = BlockMapCodec.decodeGzip(newBlockMapBytes)
        val oldBlockMap = cache.readBlockMap() ?: fetchOldBlockMap(target, cached)

        val plan = DownloadPlanBuilder.build(oldBlockMap, newBlockMap)
        checkSizeInvariant(plan, trailerLength = 0, target = target)
        return Prepared(DifferentialRequest(target.url, plan, cached.artifact, destination), newBlockMapBytes)
    }

    /**
     * Fetches the cached artifact's blockmap from the server using the version and file name
     * recorded when it was downloaded. Only useful while the host still serves the old release.
     */
    private fun fetchOldBlockMap(
        target: UpdateFile,
        cached: UpdateCache.Entry,
    ): BlockMap {
        val url = "${provider.getDownloadUrl(cached.fileName, cached.version)}$BLOCKMAP_SUFFIX"
        if (url == "${target.url}$BLOCKMAP_SUFFIX") {
            throw UpdateException("Old and new blockmap URLs are identical ($url); cannot fetch the old blockmap")
        }
        return BlockMapCodec.decodeGzip(getBytes(url))
    }

    private fun checkSizeInvariant(
        plan: DownloadPlan,
        trailerLength: Long,
        target: UpdateFile,
    ) {
        val assembled = plan.downloadSize + plan.copySize + trailerLength
        if (assembled != target.size) {
            throw UpdateException(
                "Blockmap plan assembles $assembled bytes but the manifest declares ${target.size} for ${target.fileName}",
            )
        }
    }

    private fun getBytes(url: String): ByteArray {
        val response = send(url) { }
        if (response.statusCode() != HTTP_OK) {
            throw NetworkException("HTTP ${response.statusCode()} fetching $url")
        }
        return response.body()
    }

    private fun getRange(
        url: String,
        start: Long,
        endInclusive: Long,
    ): ByteArray {
        val response = send(url) { it.header("Range", "bytes=$start-$endInclusive") }
        if (response.statusCode() != HTTP_PARTIAL) {
            throw NetworkException(
                "HTTP ${response.statusCode()} fetching range $start-$endInclusive of $url (no partial-content support?)",
            )
        }
        return response.body()
    }

    private inline fun send(
        url: String,
        configure: (HttpRequest.Builder) -> Unit,
    ): HttpResponse<ByteArray> {
        val builder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .GET()
        provider.authHeaders().forEach { (key, value) -> builder.header(key, value) }
        configure(builder)
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val UINT_MASK = 0xFFFFFFFFL
        const val BLOCKMAP_SUFFIX = ".blockmap"
    }
}
