package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.uma.UmaProtocolCapture
import com.newoether.agora.uma.UmaRuntimeState
import com.newoether.agora.viewmodel.GenerationContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded local tools for the hlpatch server injected into the foreground game process. */
class UmaToolProvider : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "http://127.0.0.1:18765"
    private val names = setOf(
        "uma_health", "uma_status", "uma_summary", "uma_get_snapshot", "uma_get_changes",
        "uma_event_choices", "uma_event_observations", "uma_hook_diagnostics",
        "uma_event_reward_targets", "uma_ramen_transitions", "uma_protocol_metadata",
        "uma_sniff_set_enabled", "uma_sniff_clear", "uma_read_endpoint",
        "uma_search_classes", "uma_get_fields", "uma_get_methods", "uma_find_method"
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        fun bool(description: String) = ToolProperty("boolean", description)
        return listOf(
            tool("uma_health", "Check the local hlpatch SO version and health.", emptyMap()),
            tool("uma_status", "Read the small local hlpatch initialization/status snapshot.", emptyMap()),
            tool("uma_summary", "Read the current bounded Uma training state from the local SO.", emptyMap()),
            tool("uma_get_snapshot", "Read the last coherent summary captured by the Agora overlay monitor together with its structural change list.", emptyMap()),
            tool("uma_get_changes", "Read only the compact structural changes detected between the last two captured summaries.", emptyMap()),
            tool("uma_event_choices", "Read the current event-choice snapshot.", emptyMap()),
            tool("uma_event_observations", "Read completed event observations after an observation id.", mapOf(
                "after_id" to integer("Only return observations newer than this id; defaults to 0."))),
            tool("uma_hook_diagnostics", "Read the bounded hlpatch hook diagnostic endpoint.", emptyMap()),
            tool("uma_event_reward_targets", "Read the whitelisted event reward target diagnostics.", emptyMap()),
            tool("uma_ramen_transitions", "Read the bounded recent Ramen transition observations. Use only for a Ramen investigation.", emptyMap()),
            tool("uma_protocol_metadata", "Read recent sanitized protocol observations. Payloads, headers, cookies and tokens are omitted.", emptyMap()),
            tool("uma_sniff_set_enabled", "Enable or disable local protocol observation without opening a browser.", mapOf(
                "enabled" to bool("True to enable capture; false to disable it.")), listOf("enabled")),
            tool("uma_sniff_clear", "Clear the bounded local protocol observation buffers.", emptyMap()),
            tool("uma_read_endpoint", "Read any bounded, non-mutating hlpatch GET endpoint on 127.0.0.1:18765. This supports runtime, MDB, IL2CPP, diagnostics and protocol metadata endpoints. Mutating routes and credential-bearing raw sniff/private-file routes remain blocked.", mapOf(
                "path" to string("SO-relative path beginning with '/', including an optional query string."),
                "max_kib" to integer("Maximum response size in KiB, 1-1024; defaults to 256.")), listOf("path")),
            tool("uma_search_classes", "Targeted IL2CPP class-name search. Never performs a full class scan.", mapOf(
                "keyword" to string("Specific class-name keyword, 2-80 characters.")), listOf("keyword")),
            tool("uma_get_fields", "Read fields for one explicitly named IL2CPP class.", mapOf(
                "class_name" to string("Exact class name, 1-160 characters.")), listOf("class_name")),
            tool("uma_get_methods", "Read methods for one explicitly named IL2CPP class.", mapOf(
                "class_name" to string("Exact class name, 1-160 characters.")), listOf("class_name")),
            tool("uma_find_method", "Targeted search for one IL2CPP method name.", mapOf(
                "method" to string("Specific method keyword, 2-120 characters.")), listOf("method")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name !in names) return toolError("Unknown Uma tool")
        if (name == "uma_get_snapshot") return UmaRuntimeState.snapshotJson()
        if (name == "uma_get_changes") return UmaRuntimeState.changesJson()
        if (name == "uma_protocol_metadata") return runCatching {
            UmaProtocolCapture.readSanitizedMetadata()
        }.getOrElse { toolError(it.message ?: "Protocol metadata read failed") }
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return toolError("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()
        fun safeSegment(value: String, min: Int, max: Int, label: String): String {
            require(value.length in min..max) { "$label length must be $min-$max" }
            require(value.all { it.isLetterOrDigit() || it in "_.$+`<>-" }) { "$label contains unsupported characters" }
            return URLEncoder.encode(value, "UTF-8")
        }
        return runCatching {
            when (name) {
                "uma_sniff_set_enabled" -> {
                    val enabledText = (args["enabled"] as? JsonPrimitive)?.content
                    val enabled = enabledText?.toBooleanStrictOrNull()
                        ?: throw IllegalArgumentException("enabled must be true or false")
                    UmaProtocolCapture.setEnabled(enabled)
                }
                "uma_sniff_clear" -> UmaProtocolCapture.clear()
                "uma_read_endpoint" -> {
                    val path = validateReadPath(text("path"))
                    val maxKiB = text("max_kib").toIntOrNull()?.coerceIn(1, 1024) ?: 256
                    get(path, maxKiB * 1024)
                }
                else -> {
                    val path = when (name) {
                        "uma_health" -> "/health"
                        "uma_status" -> "/status"
                        "uma_summary" -> "/summary"
                        "uma_event_choices" -> "/api/event/choices"
                        "uma_event_observations" -> {
                            val after = text("after_id").toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                            "/api/event/observations?after_id=$after"
                        }
                        "uma_hook_diagnostics" -> "/debug/hookdiag"
                        "uma_event_reward_targets" -> "/debug/event_reward_targets"
                        "uma_ramen_transitions" -> "/debug/ramen_transition"
                        "uma_search_classes" -> "/classes/search/${safeSegment(text("keyword"), 2, 80, "keyword")}"
                        "uma_get_fields" -> "/fields/${safeSegment(text("class_name"), 1, 160, "class_name")}"
                        "uma_get_methods" -> "/methods/${safeSegment(text("class_name"), 1, 160, "class_name")}"
                        "uma_find_method" -> "/find_method/${safeSegment(text("method"), 2, 120, "method")}"
                        else -> throw IllegalArgumentException("Unknown Uma tool")
                    }
                    get(path, if (name == "uma_summary") 128 * 1024 else 32 * 1024)
                }
            }
        }.getOrElse { toolError(it.message ?: "Local SO request failed") }
    }

    private fun validateReadPath(input: String): String {
        require(input.startsWith('/')) { "path must begin with /" }
        require(input.length in 2..1000) { "path length must be 2-1000" }
        require(!input.startsWith("//") && "://" !in input) { "absolute/network URLs are not allowed" }
        require(input.none { it == '\r' || it == '\n' || it == '\u0000' }) { "path contains control characters" }
        val route = input.substringBefore('?').trimEnd('/').ifEmpty { "/" }
        val blocked = setOf(
            "/update", "/config", "/il2cpp/call", "/api/sniff", "/api/sniff/toggle",
            "/api/sniff/clear", "/api/event/clear", "/debug/upload", "/debug/private_file_dl"
        )
        require(route !in blocked) { "route is mutating or may expose credentials/private files" }
        require(!route.startsWith("/il2cpp/read_mem")) { "raw process-memory reads are not model-readable" }
        return input
    }

    private suspend fun get(path: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 1_500
            connection.readTimeout = 5_000
            connection.useCaches = false
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val reader = stream?.bufferedReader(Charsets.UTF_8)
                ?: throw IllegalStateException("hlpatch returned HTTP $code without a body")
            reader.use {
                val out = StringBuilder(minOf(maxChars, 8_192))
                val chunk = CharArray(2_048)
                while (true) {
                    val count = it.read(chunk)
                    if (count < 0) break
                    if (out.length + count > maxChars) throw IllegalStateException(
                        "hlpatch response exceeded the ${maxChars / 1024} KiB limit")
                    out.append(chunk, 0, count)
                }
                if (code !in 200..299) throw IllegalStateException("hlpatch HTTP $code: ${out.take(300)}")
                out.toString()
            }
        } finally { connection.disconnect() }
    }

    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name = name, description = description,
            parameters = ToolParameters(properties = properties, required = required)))

    private fun toolError(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()
    override fun handles(name: String): Boolean = name in names
}
