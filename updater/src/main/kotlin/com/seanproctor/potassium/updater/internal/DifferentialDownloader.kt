package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.exception.UpdateException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Inputs for one differential download; [trailer] is appended verbatim (embedded-blockmap formats). */
internal class DifferentialRequest(
    val url: String,
    val plan: DownloadPlan,
    val oldFile: File,
    val destination: File,
    val trailer: ByteArray? = null,
)

/**
 * Executes a [DownloadPlan], assembling the new file from copies of the old local file and
 * HTTP `Range` requests. Each operation's place in the output is fixed by the plan order, so
 * operations are written by position and need not run in order: copies run alongside up to
 * [MAX_CONCURRENT_RANGES] range requests, which the default HTTP/2 client multiplexes over
 * one connection. Many small ranges are then bound by bandwidth rather than by one round
 * trip each. Ranges are single `bytes=a-b` requests (the multipart/byteranges protocol is
 * deliberately not used — GitHub and S3 don't support it).
 *
 * A range that fails transiently (I/O error, 429, 5xx) is retried; an expired redirect target
 * is re-resolved through the original URL. Any other failure throws, and the caller is
 * expected to fall back to a full download.
 */
internal class DifferentialDownloader(
    private val httpClient: HttpClient,
    private val authHeaders: Map<String, String>,
) {
    /** An operation and its offset in the output file. */
    private class Placed<T : PlanOperation>(
        val operation: T,
        val position: Long,
    )

    /**
     * The URI range requests go to: the original URL until a response reveals where it
     * redirects, then that target, so later ranges skip the redirect round trip.
     */
    private class RangeSource(
        val originalUri: URI,
    ) {
        @Volatile
        var uri: URI = originalUri

        /** Re-resolutions left for expired redirect targets (shared by all workers). */
        @Volatile
        var reResolutionsLeft: Int = MAX_RE_RESOLUTIONS
    }

    suspend fun download(
        request: DifferentialRequest,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ) {
        val copies = mutableListOf<Placed<PlanOperation.Copy>>()
        val downloads = mutableListOf<Placed<PlanOperation.Download>>()
        var position = 0L
        for (operation in request.plan.operations) {
            when (operation) {
                is PlanOperation.Copy -> copies += Placed(operation, position)
                is PlanOperation.Download -> downloads += Placed(operation, position)
            }
            position += operation.length
        }
        val trailer = request.trailer

        RandomAccessFile(request.destination, "rw").use { destination ->
            destination.setLength(0)
            destination.setLength(position + (trailer?.size ?: 0))
            val output = destination.channel

            coroutineScope {
                val progress = Channel<Long>(Channel.UNLIMITED)
                launch(Dispatchers.IO) { copyFromOldFile(request.oldFile, output, copies) }
                launch(Dispatchers.IO) {
                    try {
                        downloadRanges(RangeSource(URI.create(request.url)), downloads, output, progress)
                    } finally {
                        progress.close()
                    }
                }

                // Progress is reported from the caller's coroutine, never from the workers:
                // the caller emits into a Flow, which only accepts emissions from its own
                // coroutine.
                var downloaded = 0L
                var reported = 0L
                for (bytes in progress) {
                    downloaded += bytes
                    if (downloaded - reported >= PROGRESS_STEP_BYTES) {
                        onProgress(downloaded, request.plan.downloadSize)
                        reported = downloaded
                    }
                }
                if (downloaded != reported) onProgress(downloaded, request.plan.downloadSize)
            }

            trailer?.let { writeFully(output, it, it.size, position) }
        }
    }

    private suspend fun downloadRanges(
        source: RangeSource,
        downloads: List<Placed<PlanOperation.Download>>,
        output: FileChannel,
        progress: SendChannel<Long>,
    ) {
        if (downloads.isEmpty()) return
        // The first range runs alone so it resolves any redirect once; the rest then go
        // straight to the resolved URI instead of each negotiating the redirect.
        downloadRange(source, downloads.first(), output, progress)
        val queue = Channel<Placed<PlanOperation.Download>>(Channel.UNLIMITED)
        downloads.drop(1).forEach { queue.trySend(it) }
        queue.close()
        coroutineScope {
            repeat(minOf(MAX_CONCURRENT_RANGES, downloads.size - 1)) {
                launch { for (placed in queue) downloadRange(source, placed, output, progress) }
            }
        }
    }

    private sealed interface Attempt {
        object Done : Attempt

        class Retry(
            val reason: String,
            val retryAfterMs: Long?,
        ) : Attempt
    }

    /** Downloads one range into place, retrying transient failures. */
    private suspend fun downloadRange(
        source: RangeSource,
        placed: Placed<PlanOperation.Download>,
        output: FileChannel,
        progress: SendChannel<Long>,
    ) {
        // Bytes of this range already reported; a retry rewrites the range from its start,
        // so only bytes beyond this are reported again and progress never moves backwards.
        var reported = 0L
        var attempt = 1
        while (true) {
            val result =
                try {
                    attemptRange(source, placed, output) { received ->
                        if (received > reported) {
                            progress.send(received - reported)
                            reported = received
                        }
                    }
                } catch (e: IOException) {
                    Attempt.Retry(e.message ?: e.javaClass.simpleName, retryAfterMs = null)
                }
            if (result !is Attempt.Retry) return
            if (attempt >= MAX_ATTEMPTS) {
                throw NetworkException("${describe(placed.operation)} failed after $attempt attempts: ${result.reason}")
            }
            delay(result.retryAfterMs ?: (BASE_RETRY_DELAY_MS shl (attempt - 1)))
            attempt++
        }
    }

    private suspend fun attemptRange(
        source: RangeSource,
        placed: Placed<PlanOperation.Download>,
        output: FileChannel,
        onReceived: suspend (Long) -> Unit,
    ): Attempt {
        val operation = placed.operation
        val uri = source.uri
        // Auth is only meant for the original host; forwarding e.g. a GitHub token to the
        // pre-signed CDN URL a redirect resolved to gets the request rejected.
        val headers = if (uri.host == source.originalUri.host) authHeaders else emptyMap()
        val request = UpdaterHttp.request(uri, headers, "bytes=${operation.start}-${operation.end - 1}")
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val status = response.statusCode()

        if (status != UpdaterHttp.HTTP_PARTIAL) {
            response.body().close()
            return failedAttempt(source, uri, response, operation)
        }

        var received = 0L
        response.body().use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (received + read > operation.length) {
                    throw NetworkException("${describe(operation)} returned more than ${operation.length} bytes")
                }
                writeFully(output, buffer, read, placed.position + received)
                received += read
                onReceived(received)
            }
        }
        if (received != operation.length) {
            throw NetworkException("${describe(operation)} returned $received bytes, expected ${operation.length}")
        }
        source.uri = response.uri()
        return Attempt.Done
    }

    /** Classifies a non-206 response: a retry for transient failures, otherwise throws. */
    private fun failedAttempt(
        source: RangeSource,
        uri: URI,
        response: HttpResponse<*>,
        operation: PlanOperation.Download,
    ): Attempt.Retry {
        val status = response.statusCode()
        return when {
            status == UpdaterHttp.HTTP_OK ->
                throw NetworkException("Server ignored the Range header (no partial-content support) for $uri")

            // A redirect target that starts refusing is most likely an expired pre-signed CDN
            // URL: go back through the original URL for a fresh one.
            (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) &&
                uri != source.originalUri &&
                source.reResolutionsLeft > 0 -> {
                synchronized(source) {
                    if (source.uri == uri) {
                        source.reResolutionsLeft--
                        source.uri = source.originalUri
                    }
                }
                Attempt.Retry("HTTP $status from $uri", retryAfterMs = 0)
            }

            status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR ->
                Attempt.Retry("HTTP $status from $uri", retryAfterMs(response))

            else -> throw NetworkException("HTTP $status downloading ${describe(operation)} from $uri")
        }
    }

    /** `Retry-After` in seconds, capped; null when absent or an HTTP date (use backoff). */
    private fun retryAfterMs(response: HttpResponse<*>): Long? =
        response
            .headers()
            .firstValue("Retry-After")
            .orElse(null)
            ?.trim()
            ?.toLongOrNull()
            ?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)
            ?.times(MILLIS_PER_SECOND)

    private fun copyFromOldFile(
        oldFile: File,
        output: FileChannel,
        copies: List<Placed<PlanOperation.Copy>>,
    ) {
        if (copies.isEmpty()) return
        RandomAccessFile(oldFile, "r").use { old ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            for (placed in copies) {
                val operation = placed.operation
                old.seek(operation.start)
                var copied = 0L
                while (copied < operation.length) {
                    val read = old.read(buffer, 0, minOf(operation.length - copied, buffer.size.toLong()).toInt())
                    if (read < 0) {
                        throw UpdateException(
                            "Old file ended prematurely while copying ${operation.start}-${operation.end}",
                        )
                    }
                    writeFully(output, buffer, read, placed.position + copied)
                    copied += read
                }
            }
        }
    }

    /** Positional write of `bytes[0, length)`; positional writes are safe from concurrent threads. */
    private fun writeFully(
        output: FileChannel,
        bytes: ByteArray,
        length: Int,
        position: Long,
    ) {
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        var offset = position
        while (buffer.hasRemaining()) {
            offset += output.write(buffer, offset)
        }
    }

    private fun describe(operation: PlanOperation.Download): String = "Range ${operation.start}-${operation.end - 1}"

    private companion object {
        const val MAX_CONCURRENT_RANGES = 6
        const val MAX_ATTEMPTS = 3
        const val BASE_RETRY_DELAY_MS = 500L
        const val MAX_RETRY_AFTER_SECONDS = 10L
        const val MILLIS_PER_SECOND = 1000L
        const val MAX_RE_RESOLUTIONS = 3
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 64L * 1024
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_SERVER_ERROR = 500
    }
}
