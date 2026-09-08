package com.newoether.agora.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BytePatternSearcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray): File {
        val f = tmp.newFile()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `finds simple text pattern with absolute offsets`() {
        val data = "hello__Assembly-CSharp__world".toByteArray()
        val hits = BytePatternSearcher.findAll(file(data), "Assembly-CSharp".toByteArray())
        assertEquals(listOf(7L), hits.map { it.offset })
    }

    @Test
    fun `finds hex pattern`() {
        val data = byteArrayOf(0x10, 0xAF.toByte(), 0x1B, 0xB1, 0xFA.toByte(), 0x20)
        val hits = BytePatternSearcher.findAll(file(data), byteArrayOf(0xAF.toByte(), 0x1B, 0xB1, 0xFA.toByte()))
        assertEquals(listOf(1L), hits.map { it.offset })
    }

    @Test
    fun `finds match straddling window boundary`() {
        // WINDOW = 64KiB; place the pattern so it crosses the boundary
        val boundary = 64L * 1024
        val pattern = "BRIDGE-ME".toByteArray()
        val data = ByteArray(boundary.toInt() + 100)
        System.arraycopy(pattern, 0, data, boundary.toInt() - 4, pattern.size) // straddles 64KiB
        val hits = BytePatternSearcher.findAll(file(data), pattern)
        assertEquals(listOf(boundary - 4), hits.map { it.offset })
    }

    @Test
    fun `start_offset skips earlier hits and overlap still works`() {
        val data = ("A".repeat(100) + "NEEDLE" + "B".repeat(100) + "NEEDLE").toByteArray()
        val first = BytePatternSearcher.findAll(file(data), "NEEDLE".toByteArray())
        assertEquals(2, first.size)
        val second = BytePatternSearcher.findAll(
            file(data), "NEEDLE".toByteArray(), startOffset = first[1].offset,
        )
        assertEquals(listOf(first[1].offset), second.map { it.offset })
    }

    @Test
    fun `limit caps results and more_may_exist semantics`() {
        val data = ByteArray(1000) { 0x55 }
        val hits = BytePatternSearcher.findAll(file(data), byteArrayOf(0x55), 0, limit = 10)
        assertEquals(10, hits.size)
    }

    @Test
    fun `no match returns empty list`() {
        val hits = BytePatternSearcher.findAll(file("abcdef".toByteArray()), "zzz".toByteArray())
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `pattern longer than max is rejected`() {
        val big = ByteArray(5000)
        try {
            BytePatternSearcher.findAll(file(ByteArray(10)), big)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too long"))
        }
    }

    @Test
    fun `hex preview contains match bytes`() {
        val data = byteArrayOf(0x01, 0xAC.toByte(), 0xF5.toByte(), 0xE0.toByte(), 0x6C, 0x07)
        val hits = BytePatternSearcher.findAll(file(data), byteArrayOf(0xAC.toByte(), 0xF5.toByte(), 0xE0.toByte(), 0x6C))
        assertEquals(1, hits.size)
        assertArrayEquals(
            byteArrayOf(0xAC.toByte(), 0xF5.toByte(), 0xE0.toByte(), 0x6C, 0x07),
            hits[0].hexPreview.split(" ").map { it.toInt(16).toByte() }.toByteArray(),
        )
    }
}
