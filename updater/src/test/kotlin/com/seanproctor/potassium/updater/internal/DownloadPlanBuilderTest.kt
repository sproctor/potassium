package com.seanproctor.potassium.updater.internal

import org.junit.Assert.assertArrayEquals
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

    @Test
    fun `coalesce merges downloads across a small copy gap`() {
        val plan =
            plan(
                PlanOperation.Download(0, 10),
                PlanOperation.Copy(500, 520),
                PlanOperation.Copy(900, 905),
                PlanOperation.Download(35, 50),
            )

        val merged = DownloadPlanBuilder.coalesce(plan, maxGap = 25)

        assertEquals(listOf<PlanOperation>(PlanOperation.Download(0, 50)), merged.operations)
        assertEquals(50L, merged.downloadSize)
        assertEquals(0L, merged.copySize)
    }

    @Test
    fun `coalesce chains merges across several gaps`() {
        val plan =
            plan(
                PlanOperation.Download(0, 10),
                PlanOperation.Copy(100, 105),
                PlanOperation.Download(15, 20),
                PlanOperation.Copy(200, 205),
                PlanOperation.Download(25, 30),
            )

        val merged = DownloadPlanBuilder.coalesce(plan, maxGap = 5)

        assertEquals(listOf<PlanOperation>(PlanOperation.Download(0, 30)), merged.operations)
    }

    @Test
    fun `coalesce keeps copies larger than the gap`() {
        val plan =
            plan(
                PlanOperation.Download(0, 10),
                PlanOperation.Copy(500, 530),
                PlanOperation.Download(40, 50),
            )

        assertEquals(plan, DownloadPlanBuilder.coalesce(plan, maxGap = 29))
    }

    @Test
    fun `coalesce keeps copies at either end of the plan`() {
        val plan =
            plan(
                PlanOperation.Copy(0, 5),
                PlanOperation.Download(5, 10),
                PlanOperation.Copy(10, 15),
            )

        assertEquals(plan, DownloadPlanBuilder.coalesce(plan, maxGap = 100))
    }

    @Test
    fun `coalesce leaves a gap that does not span the downloads alone`() {
        // The copies add up to 5 bytes, but the downloads are 20 apart in the new file.
        val plan =
            plan(
                PlanOperation.Download(0, 10),
                PlanOperation.Copy(100, 105),
                PlanOperation.Download(30, 40),
            )

        assertEquals(plan, DownloadPlanBuilder.coalesce(plan, maxGap = 100))
    }

    @Test
    fun `coalesced plan assembles the same bytes`() {
        // Old: a(10) b(10) c(10) d(10). New: a x b y c z d — downloads interleaved with 10-byte copies.
        val old = blockMap("a" to 10, "b" to 10, "c" to 10, "d" to 10)
        val new = blockMap("a" to 10, "x" to 3, "b" to 10, "y" to 4, "c" to 10, "z" to 5, "d" to 10)
        val oldBytes = ByteArray(40) { it.toByte() }
        val newBytes =
            oldBytes.copyOfRange(0, 10) + byteArrayOf(-1, -2, -3) + oldBytes.copyOfRange(10, 20) +
                byteArrayOf(-4, -5, -6, -7) + oldBytes.copyOfRange(20, 30) +
                byteArrayOf(-8, -9, -10, -11, -12) + oldBytes.copyOfRange(30, 40)
        val plan = DownloadPlanBuilder.build(old, new)

        val merged = DownloadPlanBuilder.coalesce(plan, maxGap = 10)

        assertEquals(
            listOf(PlanOperation.Copy(0, 10), PlanOperation.Download(10, 42), PlanOperation.Copy(30, 40)),
            merged.operations,
        )
        assertEquals(plan.downloadSize + plan.copySize, merged.downloadSize + merged.copySize)
        assertArrayEquals(newBytes, execute(merged, oldBytes, newBytes))
        assertArrayEquals(newBytes, execute(plan, oldBytes, newBytes))
    }

    /** Runs [plan] in order: copies read [old], downloads read [new] (the server's file). */
    private fun execute(
        plan: DownloadPlan,
        old: ByteArray,
        new: ByteArray,
    ): ByteArray =
        plan.operations.fold(ByteArray(0)) { out, operation ->
            val source = if (operation is PlanOperation.Copy) old else new
            out + source.copyOfRange(operation.start.toInt(), operation.end.toInt())
        }

    private fun plan(vararg operations: PlanOperation): DownloadPlan =
        DownloadPlan(
            operations.toList(),
            downloadSize = operations.filterIsInstance<PlanOperation.Download>().sumOf { it.length },
            copySize = operations.filterIsInstance<PlanOperation.Copy>().sumOf { it.length },
        )

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
