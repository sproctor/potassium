package com.seanproctor.potassium.updater.internal

import java.io.File

/**
 * Persists a marker file before an update install so the next launch
 * can detect that the application was just updated.
 *
 * The marker is stored in the platform-specific application data directory
 * resolved by [AppDirs].
 */
internal object UpdateMarker {
    private const val MARKER_FILE_NAME = "potassium-update-event"
    private const val KEY_PREVIOUS_VERSION = "previousVersion"
    private const val KEY_NEW_VERSION = "newVersion"

    fun write(
        previousVersion: String,
        newVersion: String,
    ) {
        val file = markerFile()
        file.parentFile?.mkdirs()
        // Trim on the way in: [read] trims on the way out, and a value carrying a newline would
        // otherwise split the record across lines and make the file unreadable.
        file.writeText(
            "$KEY_PREVIOUS_VERSION=${previousVersion.trim()}\n$KEY_NEW_VERSION=${newVersion.trim()}\n",
        )
    }

    fun read(): Pair<String, String>? {
        val file = markerFile()
        if (!file.isFile) return null
        return try {
            val props =
                file
                    .readLines()
                    // Tolerate blank and malformed lines rather than failing the whole read: a
                    // marker written by an older version could contain a value with a newline in it.
                    .filter { it.isNotBlank() && it.contains('=') }
                    .associate { line ->
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

    private fun markerFile(): File = File(AppDirs.dataDir(), MARKER_FILE_NAME)
}
