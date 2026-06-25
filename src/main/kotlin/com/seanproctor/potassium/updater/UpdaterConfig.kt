package com.seanproctor.potassium.updater

import com.seanproctor.potassium.updater.provider.UpdateProvider
import java.net.http.HttpClient

public class UpdaterConfig {
    public var currentVersion: String =
        System.getProperty("app.version")
            ?: System.getProperty("jpackage.app-version")
            ?: DEV_VERSION
    public lateinit var provider: UpdateProvider
    public var channel: String = "latest"
    public var allowDowngrade: Boolean = false
    public var allowPrerelease: Boolean = false
    public var executableType: InstallType? = null

    /**
     * Custom HTTP client used for all update checks and downloads.
     * Defaults to a standard client with redirect following enabled.
     */
    public var httpClient: HttpClient? = null

    internal fun resolvedAllowPrerelease(): Boolean = allowPrerelease || currentVersion.contains("-")

    internal fun isDevMode(): Boolean = currentVersion == DEV_VERSION

    public companion object {
        public const val DEV_VERSION: String = "0.0.0-dev"
    }
}

public fun PotassiumUpdater(block: UpdaterConfig.() -> Unit): PotassiumUpdater {
    val config = UpdaterConfig().apply(block)
    return PotassiumUpdater(config)
}
