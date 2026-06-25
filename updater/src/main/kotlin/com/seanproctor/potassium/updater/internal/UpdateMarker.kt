package com.seanproctor.potassium.updater.internal

import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Persists a marker file before an update install so the next launch
 * can detect that the application was just updated.
 *
 * The marker is stored in the platform-specific application data directory resolved from the
 * application id (system property `app.id`, else a per-install id derived from the bundled
 * runtime path):
 * - Linux:   `$XDG_DATA_HOME/<appId>/` or `~/.local/share/<appId>/`
 * - macOS:   `~/Library/Application Support/<appId>/`
 * - Windows: `%APPDATA%/<appId>/`
 */
internal object UpdateMarker {
    private const val MARKER_FILE_NAME = "nucleus-update-event"
    private const val KEY_PREVIOUS_VERSION = "previousVersion"
    private const val KEY_NEW_VERSION = "newVersion"
    private const val DEFAULT_APP_ID = "potassium-app"
    private const val HASH_LENGTH = 12

    fun write(
        previousVersion: String,
        newVersion: String,
    ) {
        val file = markerFile()
        file.parentFile?.mkdirs()
        file.writeText("$KEY_PREVIOUS_VERSION=$previousVersion\n$KEY_NEW_VERSION=$newVersion\n")
    }

    fun read(): Pair<String, String>? {
        val file = markerFile()
        if (!file.isFile) return null
        return try {
            val props =
                file.readLines().associate { line ->
                    val (key, value) = line.split("=", limit = 2)
                    key.trim() to value.trim()
                }
            val previous = props[KEY_PREVIOUS_VERSION] ?: return null
            val newVer = props[KEY_NEW_VERSION] ?: return null
            previous to newVer
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }
    }

    fun exists(): Boolean = markerFile().isFile

    fun delete() {
        markerFile().delete()
    }

    private fun markerFile(): File = File(resolveDataDir(), MARKER_FILE_NAME)

    private fun appId(): String =
        System.getProperty("app.id")?.takeIf { it.isNotBlank() }
            ?: derivedAppId()

    /**
     * When no `app.id` is configured, derive a stable id that is unique to this installation, so
     * two unrelated apps never share an update-marker directory. The bundled jpackage runtime
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

    private fun resolveDataDir(): File {
        val appId = appId()
        val os = System.getProperty("os.name", "").lowercase()
        val home = System.getProperty("user.home")

        return when {
            os.contains("win") -> {
                val appData = System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
                File(appData, appId)
            }

            os.contains("mac") -> {
                File(home, "Library/Application Support/$appId")
            }

            else -> {
                val xdgData = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
                File(xdgData, appId)
            }
        }
    }
}
