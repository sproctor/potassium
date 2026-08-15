package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.provider.UpdateProvider
import java.io.File
import java.net.http.HttpClient

public class UpdaterConfig {
    public var currentVersion: String =
        System.getProperty("app.version")
            ?: System.getProperty("jpackage.app-version")
            ?: DEV_VERSION
    public lateinit var provider: UpdateProvider
    public var channel: String? = null
    public var allowDowngrade: Boolean = false
    public var executableType: InstallType? = null

    /**
     * Custom HTTP client used for all update checks and downloads.
     * Defaults to a standard client with redirect following enabled.
     *
     * A custom client **must** enable redirect following (e.g.
     * `HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()`):
     * providers such as GitHub Releases redirect downloads to a CDN, and
     * `HttpClient.newHttpClient()` follows no redirects by default, which breaks
     * downloads and silently disables differential updates.
     */
    public var httpClient: HttpClient? = null

    /**
     * Disables blockmap-based differential (delta) downloads and the local artifact caching
     * that supports them; every update is downloaded in full, and any previously cached
     * artifact is cleared on the next update. Differential downloads are enabled by default
     * and fall back to a full download automatically on any failure.
     */
    public var disableDifferentialDownload: Boolean = false

    /** Overrides the directory holding the differential-update artifact cache (mainly a test seam). */
    internal var updateCacheDir: File? = null

    /**
     * Validates the config and freezes it into an immutable snapshot, so a [PotassiumUpdater]
     * never observes post-construction mutation and a missing [provider] fails at construction
     * instead of at the first network call.
     */
    internal fun resolve(): ResolvedUpdaterConfig {
        require(::provider.isInitialized) {
            "UpdaterConfig.provider must be set, e.g. PotassiumUpdater { provider = GitHubProvider(\"owner/repo\") }"
        }
        return ResolvedUpdaterConfig(
            currentVersion = currentVersion,
            provider = provider,
            channel = channel,
            allowDowngrade = allowDowngrade,
            executableType = executableType,
            httpClient = httpClient,
            disableDifferentialDownload = disableDifferentialDownload,
            updateCacheDir = updateCacheDir,
        )
    }

    public companion object {
        public const val DEV_VERSION: String = "0.0.0-dev"
    }
}

/** The immutable snapshot of an [UpdaterConfig], taken once when a [PotassiumUpdater] is constructed. */
internal data class ResolvedUpdaterConfig(
    val currentVersion: String,
    val provider: UpdateProvider,
    val channel: String?,
    val allowDowngrade: Boolean,
    val executableType: InstallType?,
    val httpClient: HttpClient?,
    val disableDifferentialDownload: Boolean,
    val updateCacheDir: File?,
) {
    fun isDevMode(): Boolean = currentVersion == UpdaterConfig.DEV_VERSION
}

public fun PotassiumUpdater(block: UpdaterConfig.() -> Unit): PotassiumUpdater {
    val config = UpdaterConfig().apply(block)
    return PotassiumUpdater(config)
}
