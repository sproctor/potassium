package com.seanproctor.potassium.updater.internal

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Resolves the platform-specific application data directory used for updater state
 * (update markers, cached artifacts). The directory is derived from the application id
 * (system property `app.id`, else a per-install id derived from the bundled runtime path):
 * - Linux:   `$XDG_DATA_HOME/<appId>/` or `~/.local/share/<appId>/`
 * - macOS:   `~/Library/Application Support/<appId>/`
 * - Windows: `%APPDATA%/<appId>/`
 */
internal object AppDirs {
    private const val DEFAULT_APP_ID = "potassium-app"
    private const val HASH_LENGTH = 12

    fun dataDir(): File {
        val appId = appId()
        val os = System.getProperty("os.name", "").lowercase()
        val home = System.getProperty("user.home")

        // Empty-but-set variables are treated as unset (the XDG spec requires it, and an
        // empty APPDATA would resolve relative to the working directory).
        return when {
            os.contains("win") -> {
                val appData =
                    System.getenv("APPDATA")?.takeIf { it.isNotEmpty() } ?: "$home\\AppData\\Roaming"
                File(appData, appId)
            }

            os.contains("mac") -> {
                File(home, "Library/Application Support/$appId")
            }

            else -> {
                val xdgData =
                    System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotEmpty() } ?: "$home/.local/share"
                File(xdgData, appId)
            }
        }
    }

    /**
     * Per-application scratch directory inside the system temp directory, for files that only
     * need to outlive the app process — currently the script that applies an update.
     *
     * Namespaced by [appId] because `java.io.tmpdir` is `/tmp` on Linux and macOS, shared by
     * every user and application on the machine: two apps writing a fixed file name there would
     * overwrite each other mid-update.
     */
    fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), appId())

    internal fun appId(): String =
        System.getProperty("app.id")?.takeIf { it.isNotBlank() }
            ?: derivedAppId()

    /**
     * When no `app.id` is configured, derive a stable id that is unique to this installation, so
     * two unrelated apps never share an updater state directory. The bundled jpackage runtime
     * path (`java.home`) is unique per install; we hash it for uniqueness and prefix the install
     * directory name for readability — e.g. `myapp-3f2a1b9c4d5e`.
     */
    private fun derivedAppId(): String {
        // Prefer the bundled jpackage runtime path. Fall back to the launcher / native-image
        // executable path, which exists for GraalVM native images (where java.home is absent).
        System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let { javaHome ->
            val name = installRoot(File(javaHome))?.name?.sanitizedId() ?: DEFAULT_APP_ID
            return "$name-${shortHash(javaHome)}"
        }
        ProcessHandle.current().info().command().orElse(null)?.takeIf { it.isNotBlank() }?.let { executable ->
            val name = File(executable).nameWithoutExtension.sanitizedId()
            return "$name-${shortHash(executable)}"
        }
        return DEFAULT_APP_ID
    }

    /** Best-effort application install root from the bundled-runtime [javaHome] (jpackage layouts). */
    private fun installRoot(javaHome: File): File? {
        // macOS: .../<App>.app/Contents/runtime/Contents/Home
        generateSequence(javaHome) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }?.let { return it }
        // Linux: <App>/lib/runtime ; Windows: <App>\runtime
        return if (javaHome.parentFile?.name == "lib") javaHome.parentFile?.parentFile else javaHome.parentFile
    }

    private fun String.sanitizedId(): String =
        removeSuffix(".app")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { DEFAULT_APP_ID }

    private fun shortHash(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray())).take(HASH_LENGTH)
}
