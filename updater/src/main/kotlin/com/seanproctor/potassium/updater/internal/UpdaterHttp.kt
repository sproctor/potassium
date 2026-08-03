package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.NetworkException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

/** Shared request construction and status constants for the updater's HTTP calls. */
internal object UpdaterHttp {
    const val HTTP_OK: Int = 200
    const val HTTP_PARTIAL: Int = 206

    /**
     * Response-header-phase timeout applied to every request built here (body reads are
     * not covered by [HttpRequest.timeout], so large transfers are unaffected). Prevents
     * a server that accepts the connection but never responds from hanging an update.
     */
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

    fun request(
        url: String,
        authHeaders: Map<String, String>,
        range: String? = null,
    ): HttpRequest = request(URI.create(url), authHeaders, range)

    fun request(
        uri: URI,
        authHeaders: Map<String, String>,
        range: String? = null,
    ): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
        authHeaders.forEach { (key, value) -> builder.header(key, value) }
        if (range != null) {
            builder.header("Range", range)
        }
        return builder.build()
    }

    /**
     * The sidecar blockmap URL for an artifact: `.blockmap` is appended to the URL path,
     * before any query string (matching electron-updater, so pre-signed URLs survive).
     */
    fun blockMapUrl(artifactUrl: String): String {
        val queryStart = artifactUrl.indexOf('?')
        return if (queryStart < 0) {
            "$artifactUrl.blockmap"
        } else {
            artifactUrl.substring(0, queryStart) + ".blockmap" + artifactUrl.substring(queryStart)
        }
    }

    /** Reads [input] fully, throwing instead of buffering more than [maxBytes]. */
    fun readBounded(
        input: InputStream,
        maxBytes: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            if (total > maxBytes) {
                throw NetworkException("Response exceeded the expected maximum of $maxBytes bytes")
            }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
