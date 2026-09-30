package com.seanproctor.potassium.updater.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Scripted outcomes for upcoming range requests, consumed one per request: a status code
     * answers with that status (and [retryAfter], if set) instead of the range; [SERVE]
     * serves the range normally. Once empty, every range is served normally.
     */
    val scriptedStatuses: ConcurrentLinkedQueue<Int> = ConcurrentLinkedQueue()

    /** When set, every range request is answered with this status instead of the range. */
    @Volatile
    var alwaysStatus: Int? = null

    /** `Retry-After` header sent with scripted and [alwaysStatus] failures. */
    @Volatile
    var retryAfter: String? = null

    /** Delay before answering each request, so concurrent requests overlap measurably. */
    @Volatile
    var latencyMs: Long = 0

    private val inFlight = AtomicInteger(0)

    /** The most range requests ever being handled at the same time. */
    val maxInFlight: AtomicInteger = AtomicInteger(0)

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
        val current = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(current) { a, b -> maxOf(a, b) }
        try {
            if (latencyMs > 0) Thread.sleep(latencyMs)
            serve(exchange)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun serve(exchange: HttpExchange) {
        val range = exchange.requestHeaders.getFirst("Range")
        requests.add(
            RecordedRequest(
                path = exchange.requestURI.path,
                range = range,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
            ),
        )

        if (range != null) {
            val status = alwaysStatus ?: scriptedStatuses.poll()
            if (status != null && status != SERVE) {
                retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
                return
            }
        }

        val match = if (ignoreRange || range == null) null else RANGE_PATTERN.matchEntire(range)
        if (match == null) {
            synchronized(this) { bytesServed += body.size }
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
        synchronized(this) { bytesServed += slice.size }
        exchange.sendResponseHeaders(206, slice.size.toLong())
        exchange.responseBody.use { it.write(slice) }
    }

    companion object {
        /** [scriptedStatuses] entry that serves the range normally. */
        const val SERVE: Int = 0

        private val RANGE_PATTERN = Regex("""bytes=(\d+)-(\d+)""")
    }
}
