package com.seanproctor.potassium.updater.internal

/**
 * One step of a differential download. Ranges are half-open (`[start, end)`), but note the
 * asymmetry inherited from electron-updater's plan format: [Copy] offsets index the OLD
 * file, while [Download] offsets index the NEW file.
 */
internal sealed interface PlanOperation {
    val start: Long
    val end: Long

    val length: Long get() = end - start

    /** Copy `[start, end)` of the old file into the output. */
    data class Copy(
        override val start: Long,
        override val end: Long,
    ) : PlanOperation

    /** Download `[start, end)` of the new file into the output. */
    data class Download(
        override val start: Long,
        override val end: Long,
    ) : PlanOperation
}

internal data class DownloadPlan(
    val operations: List<PlanOperation>,
    val downloadSize: Long,
    val copySize: Long,
)

/**
 * Port of electron-updater's `downloadPlanBuilder.ts`: compares an old and a new blockmap and
 * produces the sequential recipe that assembles the new file from local copies and ranged
 * downloads. Checksums are compared as opaque strings; a match additionally requires equal
 * block size. Executed in order, the operations produce the new file byte-for-byte.
 */
internal object DownloadPlanBuilder {
    fun build(
        oldBlockMap: BlockMap,
        newBlockMap: BlockMap,
    ): DownloadPlan {
        // First occurrence wins for duplicated checksums, matching electron-updater.
        val oldBlocksByChecksum = HashMap<String, Block>()
        for (block in oldBlockMap.blocks()) {
            oldBlocksByChecksum.putIfAbsent(block.checksum, block)
        }

        val operations = mutableListOf<PlanOperation>()
        var downloadSize = 0L
        var copySize = 0L

        for (newBlock in newBlockMap.blocks()) {
            val oldBlock = oldBlocksByChecksum[newBlock.checksum]?.takeIf { it.size == newBlock.size }
            val last = operations.lastOrNull()
            if (oldBlock == null) {
                downloadSize += newBlock.size
                if (last is PlanOperation.Download && last.end == newBlock.offset) {
                    operations[operations.lastIndex] = last.copy(end = last.end + newBlock.size)
                } else {
                    operations += PlanOperation.Download(newBlock.offset, newBlock.offset + newBlock.size)
                }
            } else {
                copySize += newBlock.size
                if (last is PlanOperation.Copy && last.end == oldBlock.offset) {
                    operations[operations.lastIndex] = last.copy(end = last.end + newBlock.size)
                } else {
                    operations += PlanOperation.Copy(oldBlock.offset, oldBlock.offset + oldBlock.size)
                }
            }
        }

        return DownloadPlan(operations, downloadSize, copySize)
    }
}
