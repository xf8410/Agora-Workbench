package com.newoether.agora.audit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryAnalyzersTest {

    @Test
    fun il2cpp_header_parses_version_and_sections() {
        val header = ByteBuffer.allocate(8 + 2 * 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xFAB11BAF.toInt())
            .putInt(29)
            .putInt(0x200).putInt(0x100)
            .putInt(0x300).putInt(0x80)
            .array()
        val analysis = BinaryAnalyzers.analyzeIl2CppMetadataHeader(header)
        assertEquals(29, analysis.version)
        assertEquals(2, analysis.sections.size)
        assertEquals("stringLiteral", analysis.sections[0].name)
        assertEquals(0x200L, analysis.sections[0].offset)
        assertEquals("stringLiteralData", analysis.sections[1].name)
        assertEquals(0x80L, analysis.sections[1].byteCount)
    }

    @Test
    fun il2cpp_header_rejects_foreign_magic() {
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putLong(0L).array()
        var thrown = false
        try {
            BinaryAnalyzers.analyzeIl2CppMetadataHeader(header)
        } catch (expected: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("foreign magic must be rejected", thrown)
    }

    @Test
    fun elf_header_parses() {
        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            .put(0, 0x7f.toByte())
            .put(1, 'E'.code.toByte())
            .put(2, 'L'.code.toByte())
            .put(3, 'F'.code.toByte())
            .put(4, 2.toByte())
            .put(5, 1.toByte())
            .putShort(16, 3)
            .putShort(18, 183)
            .putLong(24, 0x1000L)
            .putLong(32, 64L)
            .putLong(40, 0L)
            .array()
        val analysis = BinaryAnalyzers.analyzeElf(header)
        assertTrue(analysis.is64Bit)
        assertEquals("little", analysis.endian)
        assertEquals(183, analysis.machine)
        assertEquals(0x1000L, analysis.entryPoint)
    }
}
