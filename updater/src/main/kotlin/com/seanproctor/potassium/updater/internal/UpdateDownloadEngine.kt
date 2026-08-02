package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.DownloadProgress
import com.seanproctor.potassium.updater.InstallType
import com.seanproctor.potassium.updater.UpdateFile
import com.seanproctor.potassium.updater.UpdateInfo
import com.seanproctor.potassium.updater.UpdaterConfig
import com.seanproctor.potassium.updater.exception.ChecksumException
import com.seanproctor.potassium.updater.exception.NetworkException
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads an update artifact: differentially (blockmap + HTTP ranges) when possible,
 * with a full download as the universal fallback. Verifies the SHA-512, moves the file
 * into place, and caches it so the *next* update can be differential too.
 */
internal class UpdateDownloadEngine(
    private val httpClient: HttpClient,
    private val config: UpdaterConfig,
    private val resolveInstallType: () -> InstallType?,
    private val cacheFactory: () -> UpdateCache,
) {
    class Result(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    )

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
    ): Result {
        val outcome =
            tryDifferentialDownload(targetFile, tempFile, emit)
                ?: downloadFullFile(targetFile, tempFile, emit)

        // Rename to final file
        if (finalFile.exists()) finalFile.delete()
        tempFile.renameTo(finalFile)

        cacheForNextUpdate(finalFile, info, targetFile, outcome.newBlockMapBytes)

        return Result(outcome.bytesDownloaded, outcome.totalBytes)
    }

    /**
     * Attempts a blockmap-based differential download into [tempFile], verifying the
     * result against the manifest SHA-512. Returns null on any failure — differential
     * downloads are an optimization, so every error falls back to the full download.
     */
    private suspend fun tryDifferentialDownload(
        targetFile: UpdateFile,
        tempFile: File,
        emit: suspend (DownloadProgress) -> Unit,
    ): Outcome? {
        if (config.disableDifferentialDownload) return null
        val preparer = DifferentialUpdatePreparer(httpClient, config.provider, cacheFactory())
        val mode = preparer.modeFor(resolveInstallType(), targetFile) ?: return null

        return try {
            val prepared = preparer.prepare(mode, targetFile, tempFile)
            val plan = prepared.request.plan
            logger.log(
                System.Logger.Level.INFO,
                "Differential update: downloading ${plan.downloadSize} of ${targetFile.size} bytes " +
                    "for ${targetFile.fileName}",
            )
            DifferentialDownloader(httpClient, config.provider.authHeaders())
                .download(prepared.request) { bytesDownloaded, totalBytes ->
                    emit(DownloadProgress(bytesDownloaded, totalBytes, percentOf(bytesDownloaded, totalBytes)))
                }
            if (!ChecksumVerifier.verify(tempFile, targetFile.sha512)) {
                throw ChecksumException(targetFile.sha512, ChecksumVerifier.computeSha512Base64(tempFile))
            }
            Outcome(plan.downloadSize, plan.downloadSize, prepared.newBlockMapBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            tempFile.delete()
            logger.log(
                System.Logger.Level.INFO,
                "Differential update failed (${e.message}); falling back to a full download",
            )
            null
        }
    }

    private suspend fun downloadFullFile(
        targetFile: UpdateFile,
        tempFile: File,
        emit: suspend (DownloadProgress) -> Unit,
    ): Outcome {
        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(targetFile.url))
                .GET()
        applyAuthHeaders(requestBuilder)
        val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() != HTTP_OK) {
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

        // Verify checksum
        if (!ChecksumVerifier.verify(tempFile, targetFile.sha512)) {
            val actual = ChecksumVerifier.computeSha512Base64(tempFile)
            tempFile.delete()
            throw ChecksumException(targetFile.sha512, actual)
        }

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
        if (config.disableDifferentialDownload) return
        if (resolveInstallType() !in CACHED_ARTIFACT_TYPES) return
        try {
            val blockMapBytes = newBlockMapBytes ?: fetchBlockMapBytesOrNull(targetFile.url)
            cacheFactory().store(finalFile, blockMapBytes, info.version, targetFile.fileName, targetFile.sha512)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A failed cache write only costs the next update its differential path.
            logger.log(System.Logger.Level.DEBUG, "Could not cache the update artifact: ${e.message}")
        }
    }

    private fun fetchBlockMapBytesOrNull(url: String): ByteArray? =
        try {
            val requestBuilder =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$url.blockmap"))
                    .GET()
            applyAuthHeaders(requestBuilder)
            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() == HTTP_OK) response.body() else null
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }

    private fun applyAuthHeaders(builder: HttpRequest.Builder) {
        config.provider.authHeaders().forEach { (key, value) ->
            builder.header(key, value)
        }
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
        const val HTTP_OK = 200
        const val PERCENT_MAX = 100.0

        val logger: System.Logger = System.getLogger("com.seanproctor.potassium.updater")

        /** Install types whose downloaded artifact is kept for the next differential update. */
        val CACHED_ARTIFACT_TYPES =
            setOf(
                InstallType.ZIP,
                InstallType.NSIS,
                InstallType.EXE,
            )
    }
}
