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

    /**
     * Merges downloads separated only by copies totalling at most [maxGap] bytes into one
     * download, fetching the gap from the server instead of copying it locally. Each range
     * request costs a round trip, which outweighs transferring a small gap, so a plan with
     * many scattered changes needs far fewer requests.
     *
     * The merge is exact: download offsets index the new file, so the copies between two
     * downloads fill `[first.end, next.start)` of it, and fetching that range yields the same
     * bytes. A gap whose copies don't add up to that span is left alone.
     */
    fun coalesce(
        plan: DownloadPlan,
        maxGap: Long = MAX_COALESCE_GAP,
    ): DownloadPlan {
        val operations = plan.operations
        val merged = mutableListOf<PlanOperation>()
        var index = 0
        while (index < operations.size) {
            val operation = operations[index]
            val last = merged.lastOrNull()
            if (operation is PlanOperation.Copy && last is PlanOperation.Download) {
                var next = index
                var gap = 0L
                while (next < operations.size && operations[next] is PlanOperation.Copy) {
                    gap += operations[next].length
                    next++
                }
                val following = operations.getOrNull(next)
                if (following is PlanOperation.Download && gap <= maxGap && following.start == last.end + gap) {
                    merged[merged.lastIndex] = PlanOperation.Download(last.start, following.end)
                    index = next + 1
                    continue
                }
            }
            merged += operation
            index++
        }
        return DownloadPlan(
            operations = merged,
            downloadSize = merged.filterIsInstance<PlanOperation.Download>().sumOf { it.length },
            copySize = merged.filterIsInstance<PlanOperation.Copy>().sumOf { it.length },
        )
    }

    /** Largest local-copy gap [coalesce] replaces with downloaded bytes. */
    const val MAX_COALESCE_GAP: Long = 256L * 1024
}
