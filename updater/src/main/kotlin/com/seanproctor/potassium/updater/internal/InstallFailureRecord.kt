package com.seanproctor.potassium.updater.internal

import java.io.File
import java.io.RandomAccessFile

/**
 * The record a detached install script leaves when the installer it ran failed, and the log of
 * that installer's output. The script discards the app's own output and relaunches whatever is
 * installed, so without these the next launch could not tell a failed install from no install.
 *
 * Both files live in the platform-specific application data directory resolved by [AppDirs]. The
 * script writes them; this side only reads and clears them. The record holds `key=value` lines:
 * the version the install tried to reach (absent when it was not known) and the exit status.
 */
internal object InstallFailureRecord {
    private const val RECORD_FILE_NAME = "potassium-install-failure"
    private const val LOG_FILE_NAME = "potassium-install.log"
    const val KEY_VERSION = "version"
    const val KEY_EXIT_CODE = "exitCode"

    /** Enough of the installer's output to show its error, which comes last. */
    private const val MAX_OUTPUT_BYTES = 16 * 1024

    data class Entry(
        val version: String?,
        val exitCode: Int,
        val output: String,
    )

    fun recordFile(): File = File(AppDirs.dataDir(), RECORD_FILE_NAME)

    fun logFile(): File = File(AppDirs.dataDir(), LOG_FILE_NAME)

    /** The recorded failure, or null when there is none or it cannot be parsed. Never throws. */
    fun read(): Entry? {
        val file = recordFile()
        if (!file.isFile) return null
        return try {
            val props =
                file
                    .readLines()
                    .filter { it.isNotBlank() && it.contains('=') }
                    .associate { line ->
                        val (key, value) = line.split("=", limit = 2)
                        key.trim() to value.trim()
                    }
            val exitCode = props[KEY_EXIT_CODE]?.toIntOrNull() ?: return null
            Entry(
                version = props[KEY_VERSION]?.takeIf { it.isNotEmpty() },
                exitCode = exitCode,
                output = readOutputTail(),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }
    }

    /** Clears the record. The log stays for diagnosis until the next install replaces it. */
    fun delete() {
        recordFile().delete()
    }

    private fun readOutputTail(): String {
        val log = logFile()
        if (!log.isFile) return ""
        RandomAccessFile(log, "r").use { raf ->
            val start = (raf.length() - MAX_OUTPUT_BYTES).coerceAtLeast(0)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            return bytes.toString(Charsets.UTF_8).trim()
        }
    }
}
