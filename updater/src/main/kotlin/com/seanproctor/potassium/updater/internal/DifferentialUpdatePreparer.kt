package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.UpdateFile
import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.exception.UpdateException
import com.seanproctor.potassium.updater.provider.UpdateProvider
import com.seanproctor.potassium.updater.runtime.Platform
import java.io.File
import java.net.http.HttpClient
import java.net.http.HttpResponse

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
    private val currentVersion: String,
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

            // NSIS_WEB is included because nsis-web installs update via the full NSIS
            // installer (see FileSelector), which has a published sidecar blockmap too.
            installType in NSIS_FAMILY && fileName.endsWith(".exe") -> Mode.SIDECAR

            else -> null
        }
    }

    fun prepare(
        mode: Mode,
        target: UpdateFile,
        newVersion: String,
        destination: File,
    ): Prepared =
        when (mode) {
            Mode.EMBEDDED -> prepareEmbedded(target, destination)
            Mode.SIDECAR -> prepareSidecar(target, newVersion, destination)
        }

    private fun prepareEmbedded(
        target: UpdateFile,
        destination: File,
    ): Prepared {
        val blockMapSize =
            target.blockMapSize
                ?: throw UpdateException("Manifest has no blockMapSize for ${target.fileName}")
        if (blockMapSize > BlockMapCodec.MAX_BLOCKMAP_BYTES) {
            throw UpdateException("Manifest blockMapSize $blockMapSize exceeds the supported maximum")
        }
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
        val declaredSize = BlockMapCodec.declaredEmbeddedSize(trailer)
        if (declaredSize != blockMapSize) {
            return "Embedded blockmap declares $declaredSize bytes, manifest says $blockMapSize"
        }
        return null
    }

    private fun prepareSidecar(
        target: UpdateFile,
        newVersion: String,
        destination: File,
    ): Prepared {
        // Cheap old-artifact discovery first, then the small new-blockmap fetch: hosts that
        // never publish sidecars fail fast, before the expensive integrity hash in cache.read().
        val hasCache = cache.hasEntry()
        val seededInstaller = if (hasCache) null else findSeededInstaller()
        if (!hasCache && seededInstaller == null) {
            throw UpdateException("No previously downloaded artifact to diff against")
        }
        val newBlockMapBytes = getBytes(UpdaterHttp.blockMapUrl(target.url))
        val newBlockMap = BlockMapCodec.decodeGzip(newBlockMapBytes)

        val (oldFile, oldBlockMap) =
            if (hasCache) {
                val cached =
                    cache.read()
                        ?: throw UpdateException("Cached artifact is missing or corrupt")
                cached.artifact to
                    (cache.readBlockMap() ?: fetchOldBlockMap(cached.fileName, cached.version, target))
            } else {
                // First update on this machine: diff against the installer the NSIS install
                // seeded; its blockmap must come from the server for the running version.
                val oldFileName = substituteVersion(target.fileName, newVersion)
                checkNotNull(seededInstaller) to fetchOldBlockMap(oldFileName, currentVersion, target)
            }

        val plan = DownloadPlanBuilder.build(oldBlockMap, newBlockMap)
        checkSizeInvariant(plan, trailerLength = 0, target = target)
        return Prepared(DifferentialRequest(target.url, plan, oldFile, destination), newBlockMapBytes)
    }

    /**
     * The previous installer that electron-builder's NSIS template copies to
     * `%LOCALAPPDATA%\<updaterCacheDirName>\installer.exe` at install time (and refreshes on
     * every install, including this updater's own silent update installs). Available from the
     * very first install onward, so Windows first updates can be differential before this
     * updater has cached anything itself. The directory name is published by the packaging
     * plugin as the `resources/updater-cache-dir` marker.
     */
    private fun findSeededInstaller(): File? {
        if (env.platform != Platform.Windows) return null
        val cacheDirName = AppResources.read(env, UPDATER_CACHE_DIR_FILE) ?: return null
        val localAppData = env.getenv("LOCALAPPDATA")?.takeIf { it.isNotEmpty() } ?: return null
        return File(localAppData, "$cacheDirName/$SEEDED_INSTALLER_NAME").takeIf { it.isFile && it.length() > 0 }
    }

    /** The old artifact's file name, derived by substituting the new version for the running one. */
    private fun substituteVersion(
        fileName: String,
        newVersion: String,
    ): String {
        val oldFileName = fileName.replace(newVersion, currentVersion)
        if (oldFileName == fileName) {
            throw UpdateException("Cannot derive the old artifact name: '$fileName' does not contain $newVersion")
        }
        return oldFileName
    }

    /**
     * Fetches the old artifact's blockmap from the server. Only useful while the host still
     * serves the old release; a 404 simply falls back to a full download.
     */
    private fun fetchOldBlockMap(
        oldFileName: String,
        oldVersion: String,
        target: UpdateFile,
    ): BlockMap {
        val url = UpdaterHttp.blockMapUrl(provider.getDownloadUrl(oldFileName, oldVersion))
        if (url == UpdaterHttp.blockMapUrl(target.url)) {
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

    private fun getBytes(url: String): ByteArray = fetch(url, range = null, expectedStatus = UpdaterHttp.HTTP_OK)

    private fun getRange(
        url: String,
        start: Long,
        endInclusive: Long,
    ): ByteArray = fetch(url, "bytes=$start-$endInclusive", UpdaterHttp.HTTP_PARTIAL)

    private fun fetch(
        url: String,
        range: String?,
        expectedStatus: Int,
    ): ByteArray {
        val request = UpdaterHttp.request(url, provider.authHeaders(), range)
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            // Status is checked before the body is consumed; closing without draining aborts
            // the transfer, so a 200-with-full-artifact answer to a range request is never
            // buffered into memory.
            if (response.statusCode() != expectedStatus) {
                throw NetworkException(
                    "HTTP ${response.statusCode()} fetching $url (expected $expectedStatus${
                        if (range != null) ", range $range" else ""
                    })",
                )
            }
            return UpdaterHttp.readBounded(body, MAX_FETCH_BYTES)
        }
    }

    private companion object {
        /** These fetches only ever carry blockmaps or trailers; anything larger is a misbehaving server. */
        const val MAX_FETCH_BYTES = BlockMapCodec.MAX_BLOCKMAP_BYTES + BlockMapCodec.TRAILER_LENGTH

        val NSIS_FAMILY = setOf(InstallType.NSIS, InstallType.EXE, InstallType.NSIS_WEB)

        /** Plugin-written resources marker holding electron-builder's updaterCacheDirName. */
        const val UPDATER_CACHE_DIR_FILE = "updater-cache-dir"

        /** electron-builder's CURRENT_APP_INSTALLER_FILE_NAME — the NSIS-seeded installer copy. */
        const val SEEDED_INSTALLER_NAME = "installer.exe"
    }
}
