package com.seanproctor.potassium.updater.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.util.Collections

/**
 * Test double: serves a byte array with single-range HTTP `Range` support
 * (`bytes=a-b` → 206 + Content-Range), recording every request. Toggles simulate
 * misbehaving servers.
 */
internal class RangeHttpHandler(
    @Volatile var body: ByteArray,
) : HttpHandler {
    /** When true, `Range` headers are ignored and the whole body is served with 200. */
    @Volatile
    var ignoreRange: Boolean = false

    /** When true, range responses are truncated to half the requested length. */
    @Volatile
    var truncateRanges: Boolean = false

    /** When true, range responses have their first byte flipped (correct length, wrong content). */
    @Volatile
    var corruptRanges: Boolean = false

    data class RecordedRequest(
        val path: String,
        val range: String?,
        val authorization: String?,
    )

    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    val rangeRequests: List<RecordedRequest> get() = requests.filter { it.range != null }

    @Volatile
    var bytesServed: Long = 0

    override fun handle(exchange: HttpExchange) {
        val range = exchange.requestHeaders.getFirst("Range")
        requests.add(
            RecordedRequest(
                path = exchange.requestURI.path,
                range = range,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
            ),
        )

        val match = if (ignoreRange || range == null) null else RANGE_PATTERN.matchEntire(range)
        if (match == null) {
            bytesServed += body.size
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            return
        }

        val start = match.groupValues[1].toInt()
        val endInclusive = match.groupValues[2].toInt()
        if (start > endInclusive || endInclusive >= body.size) {
            exchange.sendResponseHeaders(416, -1)
            exchange.close()
            return
        }

        var slice = body.copyOfRange(start, endInclusive + 1)
        if (truncateRanges && slice.size > 1) {
            slice = slice.copyOf(slice.size / 2)
        }
        if (corruptRanges) {
            slice[0] = slice[0].inc()
        }
        exchange.responseHeaders.set("Content-Range", "bytes $start-$endInclusive/${body.size}")
        bytesServed += slice.size
        exchange.sendResponseHeaders(206, slice.size.toLong())
        exchange.responseBody.use { it.write(slice) }
    }

    private companion object {
        val RANGE_PATTERN = Regex("""bytes=(\d+)-(\d+)""")
    }
}
