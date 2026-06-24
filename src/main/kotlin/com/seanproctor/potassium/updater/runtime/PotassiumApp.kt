package com.seanproctor.potassium.updater.runtime

import java.io.File
import java.util.Properties

/**
 * Application metadata injected by the packaging plugin, used by the updater to namespace its
 * "was just updated" marker. Trimmed to what the updater actually needs ([appId], [version]).
 *
 * Resolution order (first non-null wins): system property (`nucleus.app.*`), then the classpath
 * resource (`nucleus/nucleus-app.properties`). [appId] additionally falls back to the install
 * directory name, so the marker stays stable even when no metadata was injected.
 */
public object PotassiumApp {
    private const val RESOURCE_PATH = "nucleus/nucleus-app.properties"
    private const val DEFAULT_APP_ID = "potassium-app"

    private val resourceProps: Properties? by lazy { loadResource() }

    /** The application identifier (the packaging `packageName`), with an install-dir fallback. */
    @JvmStatic
    public val appId: String by lazy {
        resolve("nucleus.app.id", "app.id") ?: fallbackAppId()
    }

    /** The application version, or `null` if not configured. */
    @JvmStatic
    public val version: String? by lazy { resolve("nucleus.app.version", "app.version") }

    private fun resolve(
        systemPropKey: String,
        resourceKey: String,
    ): String? =
        System.getProperty(systemPropKey)?.takeIf { it.isNotBlank() }
            ?: resourceProps?.getProperty(resourceKey)?.takeIf { it.isNotBlank() }

    private fun fallbackAppId(): String {
        // jpackage: java.home = <app>/lib/runtime (or <app>\runtime) → use the app directory name.
        val javaHome = System.getProperty("java.home") ?: return DEFAULT_APP_ID
        val runtimeDir = File(javaHome)
        val appDir = runtimeDir.parentFile?.parentFile ?: runtimeDir.parentFile
        return appDir?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_APP_ID
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadResource(): Properties? =
        try {
            PotassiumApp::class.java.classLoader
                ?.getResourceAsStream(RESOURCE_PATH)
                ?.use { Properties().apply { load(it) } }
        } catch (_: Exception) {
            null
        }
}
