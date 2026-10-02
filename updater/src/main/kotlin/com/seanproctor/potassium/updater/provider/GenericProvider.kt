package com.seanproctor.potassium.updater.provider

import com.seanproctor.potassium.updater.internal.PlatformInfo
import com.seanproctor.potassium.updater.runtime.Platform
import java.net.URI
import java.net.URISyntaxException

/**
 * Serves updates from a static file host at [baseUrl], which must use `https`.
 *
 * @throws IllegalArgumentException when [baseUrl] is not a valid `https` URL. Plain `http` is
 *   accepted only for loopback hosts, for local testing.
 */
public class GenericProvider(
    baseUrl: String,
) : UpdateProvider {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    init {
        requireSecureBaseUrl(baseUrl)
    }

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String {
        val fileName = PlatformInfo.ymlFileName(channel, platform)
        return "$normalizedBaseUrl/$fileName"
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "$normalizedBaseUrl/$fileName"
}

/**
 * Rejects a non-`https` update origin at construction time.
 *
 * The manifest, its SHA-512 checksums and the artifact all come from this base URL. Over plain
 * `http` anyone on the network path can rewrite all of them together, so the checksums protect
 * nothing. `http` stays allowed for loopback hosts so local tests can serve fixtures without TLS.
 */
private fun requireSecureBaseUrl(baseUrl: String) {
    val uri =
        try {
            URI(baseUrl)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("GenericProvider baseUrl is not a valid URL: $baseUrl", e)
        }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "https" || (scheme == "http" && isLoopbackHost(uri.host))) {
        "GenericProvider requires an https:// baseUrl (got: $baseUrl). Over plain http, anyone on the " +
            "network path could replace the update manifest, its checksums and the artifact together. " +
            "http is allowed only for loopback hosts, for local testing."
    }
}

private fun isLoopbackHost(host: String?): Boolean =
    host != null &&
        (host.equals("localhost", ignoreCase = true) || host == "[::1]" || host == "::1" || isIpv4Loopback(host))

/**
 * Whether [host] is a dotted-quad IPv4 literal in `127.0.0.0/8`. Checked syntactically, never by
 * resolving it: `127.updates.example.com` is a remote name that merely starts with `127.`.
 */
private fun isIpv4Loopback(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == 4 &&
        octets[0] == "127" &&
        octets.all { octet -> octet.length in 1..3 && octet.all { it in '0'..'9' } && octet.toInt() <= 255 }
}
