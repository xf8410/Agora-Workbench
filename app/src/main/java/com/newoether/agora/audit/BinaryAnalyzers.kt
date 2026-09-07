package com.newoether.agora.audit

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface BinaryAnalysis {
    val format: String
    val warnings: List<String>
}

data class ElfAnalysis(
    val is64Bit: Boolean,
    val endian: String,
    val machine: Int,
    val type: Int,
    val entryPoint: Long,
    val programHeaderOffset: Long,
    val sectionHeaderOffset: Long,
    override val warnings: List<String> = emptyList()
) : BinaryAnalysis { override val format: String = "ELF" }

data class SqliteAnalysis(
    val pageSize: Int,
    val pageCount: Long,
    val textEncoding: Int,
    val isWalModeHint: Boolean,
    override val warnings: List<String> = emptyList()
) : BinaryAnalysis { override val format: String = "SQLite" }

data class Il2CppMetadataSection(val name: String, val offset: Long, val byteCount: Long)

data class Il2CppMetadataAnalysis(
    val version: Int,
    val sections: List<Il2CppMetadataSection>,
    override val warnings: List<String> = emptyList()
) : BinaryAnalysis {
    override val format: String = "IL2CPP_METADATA"
    val nonEmptySectionCount: Int get() = sections.count { it.byteCount > 0 }
}

/**
 * Header-level format detection ported from the uma-workbench audit module.
 * Pure JVM functions: no Android dependencies, no whole-file reads — callers pass
 * only the first bytes of a stream.
 */
object BinaryAnalyzers {
    private const val IL2CPP_MAGIC = 0xFAB11BAF.toInt()

    private val il2CppSectionNames = listOf(
        "stringLiteral", "stringLiteralData", "string", "events", "properties", "methods",
        "parameterDefaultValues", "fieldDefaultValues", "fieldAndParameterDefaultValueData",
        "fieldMarshaledSizes", "parameters", "fields", "genericParameters", "genericParameterConstraints",
        "genericContainers", "nestedTypes", "interfaces", "vtableMethods", "interfaceOffsets",
        "typeDefinitions", "images", "assemblies", "fieldRefs", "referencedAssemblies",
        "attributeData", "attributeDataRange", "unresolvedVirtualCallParameterTypes",
        "unresolvedVirtualCallParameterRanges", "windowsRuntimeTypeNames", "windowsRuntimeStrings",
        "exportedTypeDefinitions"
    )

    fun analyzeElf(header: ByteArray): ElfAnalysis {
        require(header.size >= 64) { "ELF header is truncated" }
        require(header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) { "Not an ELF file" }
        val is64 = header[4].toInt() == 2
        require(header[4].toInt() == 1 || is64) { "Unsupported ELF class" }
        val little = header[5].toInt() == 1
        require(header[5].toInt() == 1 || header[5].toInt() == 2) { "Unsupported ELF endian" }
        val buffer = ByteBuffer.wrap(header).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val type = buffer.getShort(16).toInt() and 0xffff
        val machine = buffer.getShort(18).toInt() and 0xffff
        val entry = if (is64) buffer.getLong(24) else buffer.getInt(24).toLong() and 0xffffffffL
        val phoff = if (is64) buffer.getLong(32) else buffer.getInt(28).toLong() and 0xffffffffL
        val shoff = if (is64) buffer.getLong(40) else buffer.getInt(32).toLong() and 0xffffffffL
        return ElfAnalysis(is64, if (little) "little" else "big", machine, type, entry, phoff, shoff)
    }

    fun analyzeSqliteHeader(header: ByteArray): SqliteAnalysis {
        require(header.size >= 100) { "SQLite header is truncated" }
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        require(header.copyOfRange(0, 16).contentEquals(magic)) { "Not a SQLite 3 database" }
        val pageSize = ((header[16].toInt() and 0xff) shl 8) or (header[17].toInt() and 0xff)
        val normalizedPageSize = if (pageSize == 1) 65536 else pageSize
        require(normalizedPageSize == 0 || normalizedPageSize in 512..32768 || normalizedPageSize == 65536) { "Invalid SQLite page size" }
        val pageCount = uint32BigEndian(header, 28)
        val textEncoding = uint32BigEndian(header, 56).toInt()
        val warnings = buildList { if (header[18].toInt() == 2 || header[19].toInt() == 2) add("SQLite database uses WAL or rollback journal mode") }
        return SqliteAnalysis(normalizedPageSize, pageCount, textEncoding, header[18].toInt() == 2, warnings)
    }

    fun analyzeIl2CppMetadataHeader(header: ByteArray): Il2CppMetadataAnalysis {
        require(header.size >= 16) { "IL2CPP metadata header is truncated" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.getInt(0) == IL2CPP_MAGIC) { "Not an IL2CPP global-metadata file" }
        val version = buffer.getInt(4)
        require(version in 16..40) { "Unsupported IL2CPP metadata version: $version" }
        val availablePairs = (header.size - 8) / 8
        val pairCount = minOf(availablePairs, il2CppSectionNames.size)
        val sections = (0 until pairCount).map { index ->
            val base = 8 + index * 8
            Il2CppMetadataSection(
                name = il2CppSectionNames[index],
                offset = buffer.getInt(base).toLong() and 0xffffffffL,
                byteCount = buffer.getInt(base + 4).toLong() and 0xffffffffL
            )
        }
        val warnings = buildList {
            if (pairCount < il2CppSectionNames.size) add("Metadata header ended after $pairCount section descriptors")
            sections.filter { it.byteCount > 0 && it.offset < 8L }.forEach { add("Section ${it.name} has a suspicious offset ${it.offset}") }
            if (version > 31) add("Metadata version $version uses a newer layout; descriptors require full-file validation")
        }
        return Il2CppMetadataAnalysis(version, sections, warnings)
    }

    private fun uint32BigEndian(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
}
