package com.newoether.agora.courier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CourierVolumePlannerTest {
    private val limits = CourierLimits(maxVolumeBytes = 100, maxTotalBytes = 1_000, maxFiles = 50)

    private fun file(name: String, size: Long) = PlannedFile("/root/$name", name, size)

    @Test
    fun `splits across volumes when the directory exceeds one volume`() {
        // 95MB 级目录按机主工单切 2 卷的等比缩影：100B 上限装不下 60+50
        val plan = CourierVolumePlanner.plan(
            listOf(file("a.bin", 60), file("b.bin", 50), file("c.bin", 10)),
            limits,
        )
        assertEquals(2, plan.size)
        assertEquals(listOf("a.bin", "c.bin"), plan[0].files.map { it.relativePath })
        assertEquals(listOf("b.bin"), plan[1].files.map { it.relativePath })
        assertEquals(70, plan[0].totalBytes)
        assertEquals(50, plan[1].totalBytes)
        assertTrue(plan.none { it.singleOversize })
    }

    @Test
    fun `a single file larger than the volume becomes its own volume`() {
        val plan = CourierVolumePlanner.plan(
            listOf(file("small.bin", 10), file("huge.bin", 250), file("tail.bin", 20)),
            limits,
        )
        assertEquals(3, plan.size)
        assertTrue(plan[1].singleOversize)
        assertEquals(listOf("huge.bin"), plan[1].files.map { it.relativePath })
        assertEquals(250, plan[1].totalBytes)
        // 相邻文件不与超大卷合并
        assertEquals(listOf("small.bin"), plan[0].files.map { it.relativePath })
        assertEquals(listOf("tail.bin"), plan[2].files.map { it.relativePath })
    }

    @Test
    fun `zero files produce an empty plan`() {
        assertTrue(CourierVolumePlanner.plan(emptyList(), limits).isEmpty())
    }

    @Test
    fun `a file exactly the volume size fits alone in one volume`() {
        val plan = CourierVolumePlanner.plan(listOf(file("exact.bin", 100)), limits)
        assertEquals(1, plan.size)
        assertEquals(100, plan[0].totalBytes)
        assertFalse(plan[0].singleOversize)
    }

    @Test
    fun `volumes are numbered from one`() {
        val plan = CourierVolumePlanner.plan(
            listOf(file("a", 60), file("b", 60), file("c", 60)),
            limits,
        )
        assertEquals(listOf(1, 2, 3), plan.map { it.partIndex })
    }

    @Test
    fun `limits reject zero volume size and negative totals`() {
        assertThrows(IllegalArgumentException::class.java) {
            CourierLimits(maxVolumeBytes = 0, maxTotalBytes = 10, maxFiles = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CourierLimits(maxVolumeBytes = 100, maxTotalBytes = 50, maxFiles = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CourierLimits(maxVolumeBytes = 100, maxTotalBytes = 100, maxFiles = 0)
        }
    }

    @Test
    fun `part names follow the part_NNN_zip convention`() {
        assertEquals("part_001.zip", CourierManifest.partName(1))
        assertEquals("part_002.zip", CourierManifest.partName(2))
        assertEquals("part_013.zip", CourierManifest.partName(13))
    }
}
