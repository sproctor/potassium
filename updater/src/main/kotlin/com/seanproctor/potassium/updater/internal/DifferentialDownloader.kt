package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.exception.UpdateException
import kotlinx.coroutines.delay
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse

/** Inputs for one differential download; [trailer] is appended verbatim (embedded-blockmap formats). */
internal class DifferentialRequest(
    val url: String,
    val plan: DownloadPlan,
    val oldFile: File,
    val destination: File,
    val trailer: ByteArray? = null,
)

/**
 * Executes a [DownloadPlan] in order, writing the new file sequentially: copy operations
 * read from the old local file, download operations issue single HTTP `Range` requests
 * (the multipart/byteranges protocol is deliberately not used — GitHub and S3 don't
 * support it, and sequential single ranges work everywhere that supports ranges at all).
 *
 * Any failure throws; the caller is expected to fall back to a full download.
 */
internal class DifferentialDownloader(
    private val httpClient: HttpClient,
    private val authHeaders: Map<String, String>,
) {
    suspend fun download(
        request: DifferentialRequest,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        val originalHost: String? = URI.create(request.url).host
        // After the first request this becomes the post-redirect URI, so subsequent ranges
        // go straight to the CDN instead of re-negotiating the redirect every time.
        var currentUri = URI.create(request.url)
        var bytesDownloaded = 0L
        var rangeRequestCount = 0

        RandomAccessFile(request.oldFile, "r").use { oldFile ->
            request.destination.outputStream().buffered().use { out ->
                for (operation in request.plan.operations) {
                    when (operation) {
                        is PlanOperation.Copy -> copyFromOldFile(oldFile, out, operation)

                        is PlanOperation.Download -> {
                            if (rangeRequestCount > 0 && rangeRequestCount % RANGE_REQUEST_BATCH == 0) {
                                // Brief pause every batch to avoid tripping rate limits
                                // (mirrors electron-updater).
                                delay(RANGE_REQUEST_PAUSE_MS)
                            }
                            rangeRequestCount++
                            currentUri =
                                downloadRange(currentUri, originalHost, operation, out) { chunkSize ->
                                    bytesDownloaded += chunkSize
                                    onProgress(bytesDownloaded, request.plan.downloadSize)
                                }
                        }
                    }
                }
                request.trailer?.let { out.write(it) }
            }
        }
    }

    /** Downloads one `[start, end)` range and returns the (possibly redirected) URI actually used. */
    private suspend fun downloadRange(
        uri: URI,
        originalHost: String?,
        operation: PlanOperation.Download,
        out: OutputStream,
        onChunk: suspend (Int) -> Unit,
    ): URI {
        // Auth is only meant for the original host; forwarding e.g. a GitHub token to the
        // pre-signed CDN URL a redirect resolved to gets the request rejected.
        val headers = if (uri.host == originalHost) authHeaders else emptyMap()
        val request = UpdaterHttp.request(uri, headers, "bytes=${operation.start}-${operation.end - 1}")
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() != UpdaterHttp.HTTP_PARTIAL) {
            response.body().close()
            throw NetworkException(
                if (response.statusCode() == UpdaterHttp.HTTP_OK) {
                    "Server ignored the Range header (no partial-content support) for $uri"
                } else {
                    "HTTP ${response.statusCode()} downloading range ${operation.start}-${operation.end - 1} from $uri"
                },
            )
        }

        var received = 0L
        response.body().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                received += read
                onChunk(read)
            }
        }
        if (received != operation.length) {
            throw NetworkException(
                "Range ${operation.start}-${operation.end - 1} returned $received bytes, expected ${operation.length}",
            )
        }
        return response.uri()
    }

    private fun copyFromOldFile(
        oldFile: RandomAccessFile,
        out: OutputStream,
        operation: PlanOperation.Copy,
    ) {
        oldFile.seek(operation.start)
        var remaining = operation.length
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = oldFile.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) {
                throw UpdateException("Old file ended prematurely while copying ${operation.start}-${operation.end}")
            }
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private companion object {
        const val RANGE_REQUEST_BATCH = 100
        const val RANGE_REQUEST_PAUSE_MS = 1000L
    }
}
