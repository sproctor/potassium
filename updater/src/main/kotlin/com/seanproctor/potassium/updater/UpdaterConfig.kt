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

    internal fun isDevMode(): Boolean = currentVersion == DEV_VERSION

    public companion object {
        public const val DEV_VERSION: String = "0.0.0-dev"
    }
}

public fun PotassiumUpdater(block: UpdaterConfig.() -> Unit): PotassiumUpdater {
    val config = UpdaterConfig().apply(block)
    return PotassiumUpdater(config)
}
