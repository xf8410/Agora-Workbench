package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
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

/** Guarded tools for the hlpatch server injected into the foreground game process.
 *
 * This deliberately does not expose arbitrary URLs or paths. Runtime-wide class scans,
 * recursive dumps and raw sniff payloads are excluded: only small, targeted endpoints are
 * available and every response has a hard byte/character limit.
 */
class UmaToolProvider : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "http://127.0.0.1:18765"
    private val names = setOf(
        "uma_health", "uma_status", "uma_summary", "uma_event_choices",
        "uma_event_observations", "uma_search_classes", "uma_get_fields",
        "uma_get_methods", "uma_find_method"
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            tool("uma_health", "Check the local hlpatch SO version and health.", emptyMap()),
            tool("uma_status", "Read the small local hlpatch initialization/status snapshot.", emptyMap()),
            tool("uma_summary", "Read the current bounded Uma training state from the local SO.", emptyMap()),
            tool("uma_event_choices", "Read the current event-choice snapshot.", emptyMap()),
            tool("uma_event_observations", "Read completed event observations after an observation id.", mapOf(
                "after_id" to integer("Only return observations newer than this id; defaults to 0."))),
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
        if (name !in names) return error("Unknown Uma tool")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return error("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()
        fun safeSegment(value: String, min: Int, max: Int, label: String): String {
            require(value.length in min..max) { "$label length must be $min-$max" }
            require(value.all { it.isLetterOrDigit() || it in "_.$+`<>-" }) { "$label contains unsupported characters" }
            return URLEncoder.encode(value, "UTF-8")
        }
        return runCatching {
            val path = when (name) {
                "uma_health" -> "/health"
                "uma_status" -> "/status"
                "uma_summary" -> "/summary"
                "uma_event_choices" -> "/api/event/choices"
                "uma_event_observations" -> {
                    val after = text("after_id").toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    "/api/event/observations?after_id=$after"
                }
                "uma_search_classes" -> "/classes/search/${safeSegment(text("keyword"), 2, 80, "keyword")}"
                "uma_get_fields" -> "/fields/${safeSegment(text("class_name"), 1, 160, "class_name")}"
                "uma_get_methods" -> "/methods/${safeSegment(text("class_name"), 1, 160, "class_name")}"
                "uma_find_method" -> "/find_method/${safeSegment(text("method"), 2, 120, "method")}"
                else -> error("Unknown Uma tool")
            }
            get(path, if (name == "uma_summary") 128 * 1024 else 32 * 1024)
        }.getOrElse { error(it.message ?: "Local SO request failed") }
    }

    private suspend fun get(path: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 1_500
            connection.readTimeout = 3_000
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
                    if (out.length + count > maxChars) {
                        throw IllegalStateException("hlpatch response exceeded the safe ${maxChars / 1024} KiB limit")
                    }
                    out.append(chunk, 0, count)
                }
                if (code !in 200..299) throw IllegalStateException("hlpatch HTTP $code: ${out.take(300)}")
                out.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String> = emptyList(),
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        )
    )

    private fun error(message: String) = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    override fun handles(name: String): Boolean = name in names
}
