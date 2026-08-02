package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.DownloadProgress
import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.UpdateFile
import com.seanproctor.potassium.updater.UpdateInfo
import com.seanproctor.potassium.updater.UpdaterConfig
import com.seanproctor.potassium.updater.exception.NetworkException
import java.io.File
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads an update artifact: differentially (blockmap + HTTP ranges) when possible,
 * with a full download as the universal fallback. Verifies the SHA-512, moves the file
 * into place, caches it so the *next* update can be differential too, and emits the
 * terminal [DownloadProgress] carrying the finished file.
 */
internal class UpdateDownloadEngine(
    private val httpClient: HttpClient,
    private val config: UpdaterConfig,
    private val resolveInstallType: () -> InstallType?,
    private val cache: UpdateCache,
) {
    private val preparer = DifferentialUpdatePreparer(httpClient, config.provider, cache)

    private class Outcome(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val newBlockMapBytes: ByteArray?,
    )

    suspend fun execute(
        info: UpdateInfo,
        targetFile: UpdateFile,
        tempFile: File,
        finalFile: File,
        emit: suspend (DownloadProgress) -> Unit,
    ) {
        val outcome =
            tryDifferentialDownload(targetFile, tempFile, emit)
                ?: downloadFullFile(targetFile, tempFile, emit)

        // Throws on failure (locked target file, cross-device issues) instead of silently
        // handing the caller a stale finalFile that never passed the checksum.
        Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        // Cache before the terminal emission: the documented usage installs (and exits the
        // process) from inside the collector as soon as file != null arrives.
        cacheForNextUpdate(finalFile, info, targetFile, outcome.newBlockMapBytes)

        emit(DownloadProgress(outcome.bytesDownloaded, outcome.totalBytes, PERCENT_MAX, finalFile))
    }

    /**
     * Attempts a blockmap-based differential download into [tempFile], verifying the
     * result against the manifest SHA-512. Returns null on any failure — differential
     * downloads are an optimization, so every error falls back to the full download.
     * Exceptions thrown by the downstream collector (via [emit]) are rethrown as-is:
     * a consumer bug must not masquerade as a differential-download failure.
     */
    private suspend fun tryDifferentialDownload(
        targetFile: UpdateFile,
        tempFile: File,
        emit: suspend (DownloadProgress) -> Unit,
    ): Outcome? {
        if (config.disableDifferentialDownload) return null
        val mode = preparer.modeFor(resolveInstallType(), targetFile) ?: return null

        var emitFailure: Throwable? = null
        return try {
            val prepared = preparer.prepare(mode, targetFile, tempFile)
            val plan = prepared.request.plan
            logger.log(
                System.Logger.Level.INFO,
                "Differential update: downloading ${plan.downloadSize} of ${targetFile.size} bytes " +
                    "for ${targetFile.fileName}",
            )
            DifferentialDownloader(httpClient, config.provider.authHeaders())
                .download(prepared.request, progressCallback(emit) { emitFailure = it })
            ChecksumVerifier.verifyOrThrow(tempFile, targetFile.sha512)
            Outcome(plan.downloadSize, plan.downloadSize, prepared.newBlockMapBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            if (e === emitFailure) throw e
            tempFile.delete()
            logger.log(
                System.Logger.Level.INFO,
                "Differential update failed (${e.message}); falling back to a full download",
            )
            null
        }
    }

    /** Wraps [emit], recording collector failures via [onEmitFailure] so they are never misread as download errors. */
    private fun progressCallback(
        emit: suspend (DownloadProgress) -> Unit,
        onEmitFailure: (Throwable) -> Unit,
    ): suspend (Long, Long) -> Unit =
        { bytesDownloaded, totalBytes ->
            try {
                emit(DownloadProgress(bytesDownloaded, totalBytes, percentOf(bytesDownloaded, totalBytes)))
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                onEmitFailure(t)
                throw t
            }
        }

    private suspend fun downloadFullFile(
        targetFile: UpdateFile,
        tempFile: File,
        emit: suspend (DownloadProgress) -> Unit,
    ): Outcome {
        val request = UpdaterHttp.request(targetFile.url, config.provider.authHeaders())
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() != UpdaterHttp.HTTP_OK) {
            response.body().close()
            throw NetworkException("HTTP ${response.statusCode()} downloading ${targetFile.url}")
        }

        val totalBytes = targetFile.size
        var bytesDownloaded = 0L

        response.body().use { inputStream ->
            tempFile.outputStream().use { outputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead
                    emit(DownloadProgress(bytesDownloaded, totalBytes, percentOf(bytesDownloaded, totalBytes)))
                }
            }
        }

        // Throws ChecksumException on mismatch; the caller's catch removes tempFile.
        ChecksumVerifier.verifyOrThrow(tempFile, targetFile.sha512)

        return Outcome(bytesDownloaded, totalBytes, newBlockMapBytes = null)
    }

    /**
     * Best-effort: keeps the downloaded artifact (and its blockmap) so the next update can
     * diff against it. Only the sidecar-blockmap formats need this — the AppImage old file
     * is the running AppImage itself.
     */
    private fun cacheForNextUpdate(
        finalFile: File,
        info: UpdateInfo,
        targetFile: UpdateFile,
        newBlockMapBytes: ByteArray?,
    ) {
        if (config.disableDifferentialDownload) {
            // Keep the documented promise that disabling differential downloads also
            // disables the cache: reclaim any artifact a previous configuration stored.
            try {
                cache.clear()
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                // Best-effort cleanup only.
            }
            return
        }
        if (preparer.modeFor(resolveInstallType(), targetFile) != DifferentialUpdatePreparer.Mode.SIDECAR) return
        try {
            val blockMapBytes = newBlockMapBytes ?: fetchBlockMapBytesOrNull(targetFile.url)
            cache.store(finalFile, blockMapBytes, info.version, targetFile.fileName, targetFile.sha512)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A failed cache write only costs the next update its differential path.
            logger.log(System.Logger.Level.DEBUG, "Could not cache the update artifact: ${e.message}")
        }
    }

    private fun fetchBlockMapBytesOrNull(url: String): ByteArray? =
        try {
            val request = UpdaterHttp.request(UpdaterHttp.blockMapUrl(url), config.provider.authHeaders())
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                if (response.statusCode() == UpdaterHttp.HTTP_OK) {
                    UpdaterHttp.readBounded(body, BlockMapCodec.MAX_BLOCKMAP_BYTES)
                } else {
                    null
                }
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }

    private fun percentOf(
        bytesDownloaded: Long,
        totalBytes: Long,
    ): Double =
        if (totalBytes > 0) {
            (bytesDownloaded.toDouble() / totalBytes * PERCENT_MAX).coerceAtMost(PERCENT_MAX)
        } else {
            0.0
        }

    private companion object {
        const val PERCENT_MAX = 100.0

        val logger: System.Logger = System.getLogger("com.seanproctor.potassium.updater")
    }
}
