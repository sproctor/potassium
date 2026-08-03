package com.seanproctor.potassium.updater.internal

import com.seanproctor.potassium.updater.exception.ParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * An electron-builder blockmap: the artifact split into content-defined chunks with a
 * checksum per chunk. Wire format (JSON, always a single `files` entry named `"file"`):
 *
 * ```json
 * {"version":"2","files":[{"name":"file","offset":0,"checksums":["..."],"sizes":[16384]}]}
 * ```
 *
 * Checksums (base64 BLAKE2b-144) are only ever compared as opaque strings between an old
 * and a new blockmap — this side never computes them. Integrity of the assembled file is
 * guaranteed by the whole-file SHA-512 from the update manifest instead.
 */
@Serializable
internal data class BlockMap(
    val version: String? = null,
    val files: List<BlockMapFileEntry> = emptyList(),
) {
    /** The single file entry that every electron-builder blockmap contains. */
    val file: BlockMapFileEntry get() = files.first()

    /** The chunks flattened to absolute offsets: chunk `i` starts where chunk `i - 1` ended. */
    fun blocks(): List<Block> {
        val entry = file
        var offset = entry.offset
        return entry.checksums.indices.map { i ->
            val block = Block(entry.checksums[i], offset, entry.sizes[i])
            offset += entry.sizes[i]
            block
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses and validates blockmap JSON; throws [ParseException] on any structural problem. */
        fun parse(text: String): BlockMap {
            val blockMap = decode(text)
            validationError(blockMap)?.let { throw ParseException(it) }
            return blockMap
        }

        private fun decode(text: String): BlockMap =
            try {
                json.decodeFromString<BlockMap>(text)
            } catch (e: SerializationException) {
                throw ParseException("Invalid blockmap JSON: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw ParseException("Invalid blockmap JSON: ${e.message}", e)
            }

        private fun validationError(blockMap: BlockMap): String? {
            val entry =
                blockMap.files.singleOrNull()
                    ?: return "Expected exactly one blockmap file entry, got ${blockMap.files.size}"
            if (entry.checksums.size != entry.sizes.size) {
                return "Blockmap checksum count (${entry.checksums.size}) " +
                    "does not match size count (${entry.sizes.size})"
            }
            if (entry.sizes.any { it <= 0 }) {
                return "Blockmap contains a non-positive block size"
            }
            return null
        }
    }
}

@Serializable
internal data class BlockMapFileEntry(
    val name: String = "file",
    val offset: Long = 0,
    // Required: a truncated map like {"files":[{}]} must fail parsing loudly instead of
    // yielding zero blocks and a misleading plan-size-invariant error later.
    val checksums: List<String>,
    val sizes: List<Long>,
)

/** One chunk of the mapped file at an absolute [offset]. */
internal data class Block(
    val checksum: String,
    val offset: Long,
    val size: Long,
)
