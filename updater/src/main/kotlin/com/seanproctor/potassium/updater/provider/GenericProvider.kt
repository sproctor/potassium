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
        (host.equals("localhost", ignoreCase = true) || host == "[::1]" || host == "::1" || host.startsWith("127."))
