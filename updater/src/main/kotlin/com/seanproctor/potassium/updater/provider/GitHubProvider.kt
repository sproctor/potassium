package com.seanproctor.potassium.updater.provider

import com.seanproctor.potassium.updater.exception.NetworkException
import com.seanproctor.potassium.updater.internal.PlatformInfo
import com.seanproctor.potassium.updater.runtime.Platform
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

public class GitHubProvider(
    public val owner: String,
    public val repo: String,
    public val token: String? = null,
) : UpdateProvider {
    /**
     * Base URL for GitHub. Exposed as `internal` so tests in this module can redirect feed and
     * download traffic to a local server; not part of the public API.
     */
    internal var baseUrl: String = "https://github.com"

    override fun getUpdateMetadataUrl(
        channel: String,
        platform: Platform,
    ): String = "$baseUrl/$owner/$repo/releases/latest/download/${PlatformInfo.ymlFileName(channel, platform)}"

    override fun resolveMetadataUrl(
        channel: String,
        platform: Platform,
        httpClient: HttpClient,
    ): String {
        val fileName = PlatformInfo.ymlFileName(channel, platform)
        // Stable uses GitHub's `releases/latest` redirect (the latest non-prerelease), like
        // electron-updater's /releases/latest — no feed lookup needed.
        if (channel.equals(LATEST_CHANNEL, ignoreCase = true)) {
            return "$baseUrl/$owner/$repo/releases/latest/download/$fileName"
        }
        // Pre-release channels: discover the newest matching tag from the public releases Atom
        // feed, exactly like electron-updater — no REST API, so no rate limit.
        val tag = findTagForChannel(channel, httpClient)
        return "$baseUrl/$owner/$repo/releases/download/$tag/$fileName"
    }

    override fun getDownloadUrl(
        fileName: String,
        version: String,
    ): String = "$baseUrl/$owner/$repo/releases/download/v$version/$fileName"

    override fun authHeaders(): Map<String, String> =
        if (token != null) mapOf("Authorization" to "token $token") else emptyMap()

    /**
     * Finds the newest release tag for [channel] by parsing GitHub's releases Atom feed
     * (`/<owner>/<repo>/releases.atom`). Entries are newest-first; a tag's channel is its first
     * semver pre-release identifier (`2.3.5-beta.8` → `beta`), matching electron-updater. The
     * feed is public, so this avoids the REST API rate limit.
     *
     * Three distinct failure modes (HTTP error, empty feed, no matching channel) each surface a
     * specific exception, so `ThrowsCount` is suppressed rather than collapsing the messages.
     */
    @Suppress("ThrowsCount")
    private fun findTagForChannel(
        channel: String,
        httpClient: HttpClient,
    ): String {
        val builder =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$baseUrl/$owner/$repo/releases.atom"))
                .header("Accept", "application/atom+xml, application/xml, text/xml, */*")
        if (token != null) builder.header("Authorization", "Bearer $token")

        val response = httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != HTTP_OK) {
            throw NetworkException("GitHub releases feed failed for $owner/$repo: HTTP ${response.statusCode()}")
        }

        // Atom entry links look like `.../releases/tag/<tag>`; findAll preserves feed (newest-first) order.
        val tags = TAG_HREF_REGEX.findAll(response.body()).map { it.groupValues[1] }.toList()
        if (tags.isEmpty()) {
            throw NoSuchElementException("No published versions for $owner/$repo on GitHub.")
        }
        return tags.firstOrNull { tagChannel(it).equals(channel, ignoreCase = true) }
            ?: throw NoSuchElementException(
                "No release found for channel '$channel' in the GitHub releases feed for $owner/$repo. " +
                    "Publish a release on this channel.",
            )
    }

    /** The channel of a tag — its first semver pre-release identifier, or "" for a stable tag. */
    private fun tagChannel(tag: String): String =
        tag.substringAfter('-', missingDelimiterValue = "").substringBefore('.')

    private companion object {
        const val LATEST_CHANNEL = "latest"
        const val HTTP_OK = 200
        val TAG_HREF_REGEX = Regex("""/releases/tag/([^"]+)""")
    }
}
