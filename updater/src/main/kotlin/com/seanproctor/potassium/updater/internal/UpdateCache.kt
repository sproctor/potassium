package com.seanproctor.potassium.updater.internal

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists the most recently downloaded update artifact (and its sidecar blockmap) so the
 * *next* update can be downloaded differentially against it. Used for the sidecar-blockmap
 * formats (macOS ZIP, Windows NSIS exe); AppImage needs no cache because the running
 * AppImage itself is the old file.
 *
 * Layout inside [dir]: `current-artifact` (the previous installer/archive bytes),
 * `current.blockmap` (its gzip blockmap, if one was available), and `cache-info`
 * (`key=value` lines recording version, fileName, and sha512). Every [store] replaces the
 * whole generation, so the cache never holds more than one artifact.
 */
internal class UpdateCache(
    private val dir: File,
) {
    constructor() : this(File(AppDirs.dataDir(), CACHE_DIR_NAME))

    internal data class Entry(
        val artifact: File,
        val version: String,
        val fileName: String,
        val sha512: String,
    )

    /**
     * Cheap check that a complete-looking generation exists, without the integrity hash
     * [read] performs. Callers can use this to fail fast before other inexpensive work.
     */
    fun hasEntry(): Boolean = File(dir, INFO_FILE_NAME).isFile && File(dir, ARTIFACT_FILE_NAME).isFile

    /**
     * Returns the cached artifact after verifying it against the recorded SHA-512,
     * or null (clearing the cache) when missing, incomplete, or corrupt.
     */
    fun read(): Entry? {
        val info = readInfo() ?: return null
        val version = info[KEY_VERSION] ?: return invalid()
        val fileName = info[KEY_FILE_NAME] ?: return invalid()
        val sha512 = info[KEY_SHA512] ?: return invalid()
        val artifact = File(dir, ARTIFACT_FILE_NAME)
        if (!artifact.isFile) return invalid()
        if (!ChecksumVerifier.verify(artifact, sha512)) return invalid()
        return Entry(artifact, version, fileName, sha512)
    }

    /** The cached artifact's blockmap, or null if absent or unreadable. */
    fun readBlockMap(): BlockMap? {
        val file = File(dir, BLOCKMAP_FILE_NAME)
        if (!file.isFile) return null
        return try {
            BlockMapCodec.decodeGzip(file.readBytes())
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }
    }

    /**
     * Replaces the cache with [artifact] and, when available, its gzip [blockMapBytes].
     * `cache-info` is written last so a partially written generation is detected as
     * invalid by [read].
     */
    fun store(
        artifact: File,
        blockMapBytes: ByteArray?,
        version: String,
        fileName: String,
        sha512: String,
    ) {
        dir.mkdirs()
        // Both metadata files go first so any torn state reads as invalid (or at worst
        // blockmap-less) rather than pairing a blockmap with the wrong artifact generation.
        File(dir, INFO_FILE_NAME).delete()
        File(dir, BLOCKMAP_FILE_NAME).delete()

        replace(ARTIFACT_FILE_NAME) { Files.copy(artifact.toPath(), it.toPath()) }
        if (blockMapBytes != null) {
            replace(BLOCKMAP_FILE_NAME) { it.writeBytes(blockMapBytes) }
        }
        replace(INFO_FILE_NAME) {
            it.writeText("$KEY_VERSION=$version\n$KEY_FILE_NAME=$fileName\n$KEY_SHA512=$sha512\n")
        }
    }

    fun clear() {
        listOf(INFO_FILE_NAME, ARTIFACT_FILE_NAME, BLOCKMAP_FILE_NAME).forEach { name ->
            File(dir, name).delete()
            // Also reclaim staging files a crash mid-store may have stranded.
            File(dir, "$name.tmp").delete()
        }
    }

    private fun invalid(): Entry? {
        clear()
        return null
    }

    private fun readInfo(): Map<String, String>? {
        val file = File(dir, INFO_FILE_NAME)
        if (!file.isFile) return null
        return try {
            file
                .readLines()
                .filter { it.contains('=') }
                .associate { line ->
                    val (key, value) = line.split("=", limit = 2)
                    key.trim() to value.trim()
                }
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            null
        }
    }

    private inline fun replace(
        name: String,
        write: (File) -> Unit,
    ) {
        val tmp = File(dir, "$name.tmp")
        tmp.delete()
        write(tmp)
        Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val CACHE_DIR_NAME = "update-cache"
        const val ARTIFACT_FILE_NAME = "current-artifact"
        const val BLOCKMAP_FILE_NAME = "current.blockmap"
        const val INFO_FILE_NAME = "cache-info"
        const val KEY_VERSION = "version"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_SHA512 = "sha512"
    }
}
