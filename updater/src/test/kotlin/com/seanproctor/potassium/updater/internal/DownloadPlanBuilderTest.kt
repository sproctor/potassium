package com.seanproctor.potassium.updater.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPlanBuilderTest {
    @Test
    fun `identical blockmaps produce a single copy`() {
        val map = blockMap("a" to 10, "b" to 20, "c" to 30)

        val plan = DownloadPlanBuilder.build(map, map)

        assertEquals(listOf<PlanOperation>(PlanOperation.Copy(0, 60)), plan.operations)
        assertEquals(0L, plan.downloadSize)
        assertEquals(60L, plan.copySize)
    }

    @Test
    fun `disjoint blockmaps produce a single download`() {
        val plan =
            DownloadPlanBuilder.build(
                blockMap("a" to 10, "b" to 20),
                blockMap("x" to 15, "y" to 25),
            )

        assertEquals(listOf<PlanOperation>(PlanOperation.Download(0, 40)), plan.operations)
        assertEquals(40L, plan.downloadSize)
        assertEquals(0L, plan.copySize)
    }

    @Test
    fun `interleaved reuse alternates copy and download at new-file vs old-file offsets`() {
        // Old file: a@0(10), b@10(20). New file: a(10), x(5), b(20).
        val plan =
            DownloadPlanBuilder.build(
                blockMap("a" to 10, "b" to 20),
                blockMap("a" to 10, "x" to 5, "b" to 20),
            )

        assertEquals(
            listOf(
                PlanOperation.Copy(0, 10), // old-file offsets
                PlanOperation.Download(10, 15), // new-file offsets
                PlanOperation.Copy(10, 30), // old-file offsets
            ),
            plan.operations,
        )
        assertEquals(5L, plan.downloadSize)
        assertEquals(30L, plan.copySize)
    }

    @Test
    fun `copies merge only when contiguous in the old file`() {
        // Old file: a@0(10), skip@10(5), b@15(20). New file: a, b adjacent.
        val plan =
            DownloadPlanBuilder.build(
                blockMap("a" to 10, "skip" to 5, "b" to 20),
                blockMap("a" to 10, "b" to 20),
            )

        assertEquals(
            listOf(
                PlanOperation.Copy(0, 10),
                PlanOperation.Copy(15, 35),
            ),
            plan.operations,
        )
    }

    @Test
    fun `reordered old blocks copy from their old offsets`() {
        // Old file: a@0(10), b@10(20). New file: b first, then a.
        val plan =
            DownloadPlanBuilder.build(
                blockMap("a" to 10, "b" to 20),
                blockMap("b" to 20, "a" to 10),
            )

        assertEquals(
            listOf(
                PlanOperation.Copy(10, 30),
                PlanOperation.Copy(0, 10),
            ),
            plan.operations,
        )
    }

    @Test
    fun `checksum match with different size is treated as a miss`() {
        val plan =
            DownloadPlanBuilder.build(
                blockMap("a" to 10),
                blockMap("a" to 12),
            )

        assertEquals(listOf<PlanOperation>(PlanOperation.Download(0, 12)), plan.operations)
    }

    @Test
    fun `duplicate checksum in old map uses first occurrence`() {
        // "dup" appears at old offsets 0 and 30; first-wins → copies come from offset 0.
        val plan =
            DownloadPlanBuilder.build(
                blockMap("dup" to 10, "b" to 20, "dup" to 10),
                blockMap("dup" to 10),
            )

        assertEquals(listOf<PlanOperation>(PlanOperation.Copy(0, 10)), plan.operations)
    }

    @Test
    fun `non-zero new offset shifts download ranges`() {
        val old = blockMap("a" to 10)
        val new = BlockMap(version = "2", files = listOf(BlockMapFileEntry("file", 100, listOf("x"), listOf(10))))

        val plan = DownloadPlanBuilder.build(old, new)

        assertEquals(listOf<PlanOperation>(PlanOperation.Download(100, 110)), plan.operations)
    }

    @Test
    fun `accounting sums download and copy sizes`() {
        val plan =
            DownloadPlanBuilder.build(
                blockMap("a" to 10, "b" to 20),
                blockMap("a" to 10, "x" to 7, "b" to 20, "y" to 3),
            )

        assertEquals(10L, plan.downloadSize)
        assertEquals(30L, plan.copySize)
        assertEquals(40L, plan.operations.sumOf { it.length })
    }

    private fun blockMap(vararg blocks: Pair<String, Int>): BlockMap =
        BlockMap(
            version = "2",
            files =
                listOf(
                    BlockMapFileEntry(
                        name = "file",
                        offset = 0,
                        checksums = blocks.map { it.first },
                        sizes = blocks.map { it.second.toLong() },
                    ),
                ),
        )
}
