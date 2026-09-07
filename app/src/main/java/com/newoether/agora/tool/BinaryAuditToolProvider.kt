package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.audit.BinaryAnalyzers
import com.newoether.agora.audit.BinaryAuditEntry
import com.newoether.agora.audit.BinaryAuditStore
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Large-binary audit tools. Lets the agent import, cache and inspect multi-megabyte game
 * binaries (libil2cpp.so, global-metadata.dat, asset bundles) that can never enter the
 * conversation or the heap. Reads are bounded windows over random-access streams, so any
 * offset of any file size is safe on phones; every failure degrades to an ok:false JSON
 * payload instead of killing the generation.
 */
class BinaryAuditToolProvider(context: Context) : ToolProvider {

    private val appContext = context.applicationContext
    private val store by lazy { BinaryAuditStore(appContext) }
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            tool("audit_import", "Import a large binary (content://, file:// or absolute path) into the on-device audit store. Streams through a 256KiB buffer, dedupes by SHA-256 (re-importing identical content is a cheap cache hit) and returns source_id, byte_length and sha256. There is no size limit.", mapOf(
                "source" to string("Content URI, file URI or absolute path of the binary."),
                "name" to string("Display name, e.g. global-metadata.dat (optional)."),
            ), listOf("source")),
            tool("audit_list", "List all binaries in the on-device audit store with ids, sizes and hashes.", emptyMap()),
            tool("audit_info", "Format-detect one stored binary from its header: IL2CPP metadata (version + section table), ELF or SQLite. Reads at most the first 512 bytes.", mapOf(
                "source_id" to string("sourceId (or unique prefix) from audit_import/audit_list."),
            ), listOf("source_id")),
            tool("audit_read_bytes", "Read a bounded byte window of a stored binary as hex + ASCII (like a hex editor). Defaults to the first 512 bytes; hard cap 64KiB per call. Use offset to walk the file.", mapOf(
                "source_id" to string("sourceId (or unique prefix)."),
                "offset" to integer("Byte offset to start from (default 0)."),
                "length" to integer("Bytes to read (default 512, max 65536)."),
            ), listOf("source_id")),
            tool("audit_extract_strings", "Extract printable ASCII strings (length >= 5) from a byte window of a stored binary, each with its absolute offset. Windows chain via next_offset: for a 16MB file call repeatedly with offset 0, 262144, 524288, ... until complete=true.", mapOf(
                "source_id" to string("sourceId (or unique prefix)."),
                "offset" to integer("Window start (default 0)."),
                "length" to integer("Window length (default 262144, max 1048576)."),
            ), listOf("source_id")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return error("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
        fun long(key: String, fallback: Long) = args[key]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: fallback
        return runCatching {
            when (name) {
                "audit_import" -> {
                    val entry = store.import(name = text("name"), open = store.openSource(text("source")))
                    entryJson(entry)
                }
                "audit_list" -> buildJsonObject {
                    val list = store.entries()
                    put("ok", true)
                    put("count", list.size)
                    putJsonArray("entries") {
                        list.forEach { e ->
                            add(buildJsonObject {
                                put("source_id", e.sourceId)
                                put("name", e.name)
                                put("byte_length", e.byteLength)
                                put("sha256", e.sha256)
                            })
                        }
                    }
                }.toString()
                else -> {
                    val entry = store.resolve(text("source_id"))
                    val file = store.fileOf(entry)
                    require(file.isFile) { "stored bytes missing for ${entry.sourceId} — re-import" }
                    when (name) {
                        "audit_info" -> auditInfo(entry, file)
                        "audit_read_bytes" -> {
                            val offset = long("offset", 0L).coerceIn(0L, entry.byteLength)
                            val length = long("length", DEFAULT_HEX_WINDOW).coerceIn(1L, MAX_HEX_WINDOW)
                            val bytes = readWindow(file, offset, length)
                            buildJsonObject {
                                put("ok", true)
                                put("name", entry.name)
                                put("byte_length", entry.byteLength)
                                put("offset", offset)
                                put("read", bytes.size)
                                put("next_offset", offset + bytes.size)
                                put("hex", bytes.toHex())
                                put("ascii", bytes.toAscii())
                            }.toString()
                        }
                        "audit_extract_strings" -> {
                            val offset = long("offset", 0L).coerceIn(0L, entry.byteLength)
                            val length = long("length", DEFAULT_STRING_WINDOW).coerceIn(1L, MAX_STRING_WINDOW)
                            val bytes = readWindow(file, offset, length)
                            val strings = extractStrings(bytes, offset)
                            buildJsonObject {
                                put("ok", true)
                                put("name", entry.name)
                                put("offset", offset)
                                put("read", bytes.size)
                                put("next_offset", offset + bytes.size)
                                put("complete", offset + bytes.size >= entry.byteLength)
                                put("string_count", strings.size)
                                putJsonArray("strings") {
                                    strings.take(MAX_STRINGS_RETURNED).forEach { s ->
                                        add(buildJsonObject {
                                            put("offset", s.offset)
                                            put("value", s.value)
                                        })
                                    }
                                }
                            }.toString()
                        }
                        else -> error("Unknown audit tool: $name")
                    }
                }
            }
        }.getOrElse { error(it.message ?: "audit tool failed") }
    }

    private fun auditInfo(entry: BinaryAuditEntry, file: File): String {
        val header = readWindow(file, 0, 512)
        val isIl2cpp = header.size >= 4 && readIntLe(header, 0) == IL2CPP_MAGIC_INT
        return try {
            if (isIl2cpp) {
                val analysis = BinaryAnalyzers.analyzeIl2CppMetadataHeader(header)
                val outOfRange = analysis.sections.count {
                    it.byteCount > 0 && (it.offset < 0 || it.offset + it.byteCount > file.length())
                }
                buildJsonObject {
                    put("ok", true)
                    put("format", analysis.format)
                    put("name", entry.name)
                    put("byte_length", entry.byteLength)
                    put("sha256", entry.sha256)
                    put("metadata_version", analysis.version)
                    put("sections_out_of_range", outOfRange)
                    putJsonArray("sections") {
                        analysis.sections.forEach { s ->
                            add(buildJsonObject {
                                put("name", s.name)
                                put("offset", s.offset)
                                put("byte_count", s.byteCount)
                            })
                        }
                    }
                    putJsonArray("warnings") {
                        analysis.warnings.forEach { add(JsonPrimitive(it)) }
                    }
                }.toString()
            } else {
                val analysis = when {
                    header.size >= 64 && header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() -> BinaryAnalyzers.analyzeElf(header)
                    header.size >= 100 && header.copyOfRange(0, 15).toString(Charsets.US_ASCII) == "SQLite format 3" -> BinaryAnalyzers.analyzeSqliteHeader(header)
                    else -> throw IllegalStateException("Unrecognized format — likely encrypted (e.g. FairGuard) or a Unity asset bundle; use audit_read_bytes to inspect the header bytes")
                }
                buildJsonObject {
                    put("ok", true)
                    put("format", analysis.format)
                    put("name", entry.name)
                    put("byte_length", entry.byteLength)
                    put("sha256", entry.sha256)
                    putJsonArray("warnings") {
                        analysis.warnings.forEach { add(JsonPrimitive(it)) }
                    }
                }.toString()
            }
        } catch (t: IllegalArgumentException) {
            error("Format detection failed: ${t.message}")
        }
    }

    private fun readWindow(file: File, offset: Long, length: Long): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            require(offset < raf.length()) { "offset $offset beyond EOF ${raf.length()}" }
            raf.seek(offset)
            val buffer = ByteArray(length.toInt())
            var read = 0
            while (read < buffer.size) {
                val n = raf.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            return if (read == buffer.size) buffer else buffer.copyOf(read)
        }
    }

    private data class FoundString(val offset: Long, val value: String)

    private fun extractStrings(bytes: ByteArray, absoluteOffset: Long): List<FoundString> {
        val result = ArrayList<FoundString>()
        val sb = StringBuilder()
        var start = -1
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xff
            if (b in 0x20..0x7e) {
                if (sb.isEmpty()) start = i
                sb.append(b.toChar())
            } else {
                if (sb.length >= MIN_STRING_LENGTH) {
                    result += FoundString(absoluteOffset + start, sb.toString())
                }
                sb.clear()
            }
        }
        if (sb.length >= MIN_STRING_LENGTH) {
            result += FoundString(absoluteOffset + start, sb.toString())
        }
        return result
    }

    private fun entryJson(e: BinaryAuditEntry) = buildJsonObject {
        put("ok", true)
        put("source_id", e.sourceId)
        put("name", e.name)
        put("byte_length", e.byteLength)
        put("sha256", e.sha256)
    }.toString()

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private fun ByteArray.toAscii(): String = map {
        val b = it.toInt() and 0xff
        if (b in 0x20..0x7e) b.toChar() else '.'
    }.joinToString("")

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name = name, description = description,
            parameters = ToolParameters(properties = properties, required = required)))

    private fun error(message: String) = buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    override fun handles(name: String) = name in toolNames

    companion object {
        val toolNames = setOf(
            "audit_import", "audit_list", "audit_info",
            "audit_read_bytes", "audit_extract_strings",
        )
        private const val IL2CPP_MAGIC_INT = 0xFAB11BAF.toInt()
        private const val DEFAULT_HEX_WINDOW = 512L
        private const val MAX_HEX_WINDOW = 64L * 1024
        private const val DEFAULT_STRING_WINDOW = 256L * 1024
        private const val MAX_STRING_WINDOW = 1024L * 1024
        private const val MIN_STRING_LENGTH = 5
        private const val MAX_STRINGS_RETURNED = 400
    }
}
