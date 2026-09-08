package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.audit.BytePatternSearcher
import com.newoether.agora.audit.BinaryAuditStore
import com.newoether.agora.audit.ZipInspector
import com.newoether.agora.viewmodel.GenerationContext
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * P0 audit extensions (2026-09-08, from the tool roadmap):
 *  - audit_search: whole-file byte-pattern search with window bridging (strings tool is
 *    window-local and misses straddling hits; this is the FairGuard boundary mapper).
 *  - audit_zip_list / audit_zip_extract: zip/APK central-directory enumeration + entry
 *    extraction into the store — pull global-metadata.dat out of a 2.3GB APK in place.
 */
class BinaryAuditP0ToolProvider(context: Context) : ToolProvider {

    private val appContext = context.applicationContext
    private val store by lazy { BinaryAuditStore(appContext) }
    private val zipInspector by lazy { ZipInspector(store) }
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            tool("audit_search", "Search a stored binary for a byte pattern across the WHOLE file (windows bridged, no boundary misses). Pattern can be hex (e.g. 'AF1BB1FA') or text (e.g. 'Assembly-CSharp'). Returns absolute offsets with hex previews — the tool for locating plaintext anchors inside encrypted metadata, or comparing encryption boundaries between channel builds.", mapOf(
                "source_id" to string("sourceId (or unique prefix) from audit_import/audit_list."),
                "pattern" to string("Hex string (even length) or plain text to search for."),
                "start_offset" to integer("Byte offset to start from (default 0)."),
                "limit" to integer("Max hits to return (default 50)."),
            ), listOf("source_id", "pattern")),
            tool("audit_zip_list", "List ZIP/APK entries of a stored binary from its central directory: names, uncompressed/compressed sizes, compression method. Works on the full 2.3GB package — listing never extracts bytes.", mapOf(
                "source_id" to string("sourceId (or unique prefix) of a stored zip/apk."),
            ), listOf("source_id")),
            tool("audit_zip_extract", "Extract matching ZIP/APK entries of a stored binary into the audit store as standalone entries (streamed, heap-safe on multi-GB packages). Filter is an exact entry name or a case-insensitive substring (e.g. 'global-metadata.dat'). Extracted entries get ids like <parent>::<entry leaf> — feed them straight to audit_info / audit_read_bytes.", mapOf(
                "source_id" to string("sourceId (or unique prefix) of a stored zip/apk."),
                "name" to string("Entry name or case-insensitive substring to match."),
                "max_entries" to integer("Max entries to extract per call (default 5)."),
            ), listOf("source_id", "name")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return error("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()
        fun long(key: String, fallback: Long) = args[key]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: fallback
        fun int(key: String, fallback: Int) = long(key, fallback.toLong()).toInt()
        return runCatching {
            when (name) {
                "audit_search" -> {
                    val entry = store.resolve(text("source_id"))
                    val patternText = text("pattern")
                    require(patternText.isNotBlank()) { "pattern is required" }
                    val needle = parsePattern(patternText)
                    val startOffset = long("start_offset", 0L).coerceIn(0L, entry.byteLength)
                    val limit = int("limit", DEFAULT_HIT_LIMIT).coerceIn(1, MAX_HIT_LIMIT)
                    val hits = BytePatternSearcher.findAll(store.fileOf(entry), needle, startOffset, limit)
                    buildJsonObject {
                        put("ok", true)
                        put("name", entry.name)
                        put("byte_length", entry.byteLength)
                        put("pattern", patternText)
                        put("pattern_kind", if (patternText.isHexPattern()) "hex" else "text")
                        put("start_offset", startOffset)
                        put("hit_count", hits.size)
                        put("limit", limit)
                        put("more_may_exist", hits.size >= limit)
                        putJsonArray("hits") {
                            hits.forEach { h ->
                                add(buildJsonObject {
                                    put("offset", h.offset)
                                    put("hex_preview", h.hexPreview)
                                })
                            }
                        }
                    }.toString()
                }
                "audit_zip_list" -> {
                    val entry = store.resolve(text("source_id"))
                    val entries = zipInspector.listEntries(store.fileOf(entry))
                    buildJsonObject {
                        put("ok", true)
                        put("name", entry.name)
                        put("byte_length", entry.byteLength)
                        put("entry_count", entries.size)
                        putJsonArray("entries") {
                            entries.forEach { e ->
                                add(buildJsonObject {
                                    put("name", e.name)
                                    put("size_bytes", e.sizeBytes)
                                    put("compressed_bytes", e.compressedBytes)
                                    put("method", e.method)
                                })
                            }
                        }
                    }.toString()
                }
                "audit_zip_extract" -> {
                    val entry = store.resolve(text("source_id"))
                    val maxEntries = int("max_entries", 5).coerceIn(1, 20)
                    val extracted = zipInspector.extractEntries(store.fileOf(entry), text("name"), maxEntries)
                    buildJsonObject {
                        put("ok", true)
                        put("source_name", entry.name)
                        put("extracted_count", extracted.size)
                        putJsonArray("extracted") {
                            extracted.forEach { e ->
                                add(buildJsonObject {
                                    put("source_id", e.sourceId)
                                    put("name", e.name)
                                    put("byte_length", e.byteLength)
                                    put("sha256", e.sha256)
                                })
                            }
                        }
                        put("next", "audit_info / audit_read_bytes on the extracted source_id")
                    }.toString()
                }
                else -> error("Unknown audit P0 tool: $name")
            }
        }.getOrElse { error(it.message ?: "audit P0 tool failed") }
    }

    /** Even-length hex string → bytes; otherwise UTF-8 text (invalid sequences replaced). */
    private fun parsePattern(pattern: String): ByteArray {
        val compact = pattern.replace(" ", "").replace(":", "")
        if (compact.isHexPattern() && compact.length >= 2) {
            return ByteArray(compact.length / 2) { i ->
                ((Character.digit(compact[i * 2], 16) shl 4) + Character.digit(compact[i * 2 + 1], 16)).toByte()
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(java.nio.ByteBuffer.wrap(pattern.toByteArray(Charsets.UTF_8))).array()
    }

    private fun String.isHexPattern(): Boolean =
        length >= 2 && length % 2 == 0 && all { Character.digit(it, 16) >= 0 }

    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name = name, description = description,
            parameters = ToolParameters(properties = properties, required = required)))

    private fun error(message: String) = buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    override fun handles(name: String) = name in toolNames

    companion object {
        val toolNames = setOf("audit_search", "audit_zip_list", "audit_zip_extract")
        private const val DEFAULT_HIT_LIMIT = 50
        private const val MAX_HIT_LIMIT = 500
    }
}
