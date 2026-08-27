package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.uma.UmaApplicationContext
import com.newoether.agora.uma.UmaProtocolCapture
import com.newoether.agora.uma.UmaRuntimeState
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.GitHubMutationConfirmation
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun umaSoReadTimeoutMs(path: String, maxBytes: Int): Int {
    val expensivePath = path.startsWith("/il2cpp/classes") ||
        path.startsWith("/scan") || path.startsWith("/dump") ||
        path.startsWith("/memory") || path.startsWith("/process") ||
        path.startsWith("/private") || path.startsWith("/storage/")
    return if (expensivePath || maxBytes > 2 * 1024 * 1024) {
        Constants.UMA_SO_LARGE_READ_TIMEOUT_MS
    } else {
        Constants.UMA_SO_SMALL_READ_TIMEOUT_MS
    }
}

/** Characters that java.net.URL treats as illegal in a path/query; the model emits them verbatim inside SQL. */
private fun needsSoUrlEncoding(c: Char): Boolean = c > '¥' || c in " \"<>{}|\\^`"

/**
 * Percent-encode only the illegal characters, preserving ?, &, =, / and existing %XX sequences,
 * so half-encoded model output (e.g. raw SQL in ?sql=) no longer produces malformed request
 * lines that the SO reports as T003 "missing parameter".
 */
internal fun normalizeSoUrl(raw: String): String {
    if (raw.none { needsSoUrlEncoding(it) }) return raw
    return buildString(raw.length + 16) {
        for (c in raw) {
            if (needsSoUrlEncoding(c)) {
                append(URLEncoder.encode(c.toString(), "UTF-8").replace("+", "%20"))
            } else {
                append(c)
            }
        }
    }
}

/** Local tools for the hlpatch server injected into the foreground game process. */
class UmaToolProvider : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "http://127.0.0.1:18765"
    private val sessionExportTools by lazy {
        UmaSessionExportToolProvider(UmaApplicationContext.require()).also { provider ->
            provider.confirm = { _, summary -> GitHubMutationConfirmation.confirm(summary) }
        }
    }
    private val names = setOf(
        "uma_health", "uma_status", "uma_summary", "uma_get_snapshot", "uma_get_changes",
        "uma_event_choices", "uma_event_observations", "uma_hook_diagnostics",
        "uma_event_reward_targets", "uma_ramen_transitions", "uma_protocol_metadata",
        "uma_sniff_set_enabled", "uma_sniff_clear", "uma_read_endpoint",
        "uma_list_classes", "uma_search_classes", "uma_get_fields", "uma_get_methods", "uma_find_method"
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        fun bool(description: String) = ToolProperty("boolean", description)
        val localTools = listOf(
            tool("uma_health", "Check the local hlpatch SO version and health.", emptyMap()),
            tool("uma_status", "Read the local hlpatch initialization/status snapshot.", emptyMap()),
            tool("uma_summary", "Read the current Uma training state from the local SO.", emptyMap()),
            tool("uma_get_snapshot", "Read the last coherent summary captured by the Agora overlay monitor with its structural changes.", emptyMap()),
            tool("uma_get_changes", "Read the structural changes detected between the last two captured summaries.", emptyMap()),
            tool("uma_event_choices", "Read the current event-choice snapshot.", emptyMap()),
            tool("uma_event_observations", "Read completed event observations after an observation id.", mapOf(
                "after_id" to integer("Only return observations newer than this id; defaults to 0."))),
            tool("uma_hook_diagnostics", "Read the hlpatch hook diagnostic endpoint.", emptyMap()),
            tool("uma_event_reward_targets", "Read event reward target diagnostics.", emptyMap()),
            tool("uma_ramen_transitions", "Read recent Ramen transition observations.", emptyMap()),
            tool("uma_protocol_metadata", "Read full protocol observation data including headers, cookies, tokens, payloads and hex.", emptyMap()),
            tool("uma_sniff_set_enabled", "Enable or disable local protocol observation.", mapOf(
                "enabled" to bool("True to enable capture; false to disable it.")), listOf("enabled")),
            tool("uma_sniff_clear", "Clear the local protocol observation buffers.", emptyMap()),
            tool("uma_read_endpoint", "Read any hlpatch GET endpoint on 127.0.0.1:18765, including sniff, private-file, process-memory and credential-bearing routes.", mapOf(
                "path" to string("SO-relative path beginning with '/', including an optional query string."),
                "max_kib" to integer("Maximum response size in KiB, 1-16384; defaults to 2048.")), listOf("path")),
            tool("uma_list_classes", "Read the complete IL2CPP class endpoint exposed by hlpatch.", mapOf(
                "max_kib" to integer("Maximum response size in KiB, 1-16384; defaults to 8192."))),
            tool("uma_search_classes", "Search IL2CPP class names.", mapOf(
                "keyword" to string("Class-name keyword, 1-500 characters.")), listOf("keyword")),
            tool("uma_get_fields", "Read fields for an IL2CPP class.", mapOf(
                "class_name" to string("Exact class name, 1-500 characters.")), listOf("class_name")),
            tool("uma_get_methods", "Read methods for an IL2CPP class.", mapOf(
                "class_name" to string("Exact class name, 1-500 characters.")), listOf("class_name")),
            tool("uma_find_method", "Search for an IL2CPP method name.", mapOf(
                "method" to string("Method keyword, 1-500 characters.")), listOf("method")),
        )
        return localTools + sessionExportTools.definitions(ctx)
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (sessionExportTools.handles(name)) return sessionExportTools.execute(name, arguments, ctx)
        if (name !in names) return toolError("Unknown Uma tool")
        if (name == "uma_get_snapshot") return UmaRuntimeState.snapshotJson()
        if (name == "uma_get_changes") return UmaRuntimeState.changesJson()
        if (name == "uma_protocol_metadata") return runCatching {
            UmaProtocolCapture.readMetadata()
        }.getOrElse { toolError(it.message ?: "Protocol metadata read failed") }
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return toolError("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty().trim()
        fun safeSegment(value: String, label: String): String {
            require(value.length in 1..500) { "$label length must be 1-500" }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "$label contains control characters" }
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
                    val maxKiB = text("max_kib").toIntOrNull()?.coerceIn(1, 16_384) ?: 2_048
                    getWithRetry(path, maxKiB * 1024)
                }
                "uma_list_classes" -> {
                    val maxKiB = text("max_kib").toIntOrNull()?.coerceIn(1, 16_384) ?: 8_192
                    getWithRetry("/il2cpp/classes", maxKiB * 1024)
                }
                else -> {
                    val path = when (name) {
                        "uma_health" -> "/health"
                        "uma_status" -> "/status"
                        "uma_summary" -> "/summary"
                        "uma_event_choices" -> "/api/event/choices"
                        "uma_event_observations" -> "/api/event/observations?after_id=${text("after_id").toLongOrNull()?.coerceAtLeast(0L) ?: 0L}"
                        "uma_hook_diagnostics" -> "/debug/hookdiag"
                        "uma_event_reward_targets" -> "/debug/event_reward_targets"
                        "uma_ramen_transitions" -> "/debug/ramen_transition"
                        "uma_search_classes" -> "/classes/search/${safeSegment(text("keyword"), "keyword")}"
                        "uma_get_fields" -> "/fields/${safeSegment(text("class_name"), "class_name")}"
                        "uma_get_methods" -> "/methods/${safeSegment(text("class_name"), "class_name")}"
                        "uma_find_method" -> "/find_method/${safeSegment(text("method"), "method")}"
                        else -> throw IllegalArgumentException("Unknown Uma tool")
                    }
                    getWithRetry(path, if (name == "uma_summary") 2 * 1024 * 1024 else 8 * 1024 * 1024)
                }
            }
        }.getOrElse { toolError(it.message ?: "Local SO request failed") }
    }

    private fun validateReadPath(input: String): String {
        require(input.startsWith('/')) { "path must begin with /" }
        require(input.length in 2..1000) { "path length must be 2-1000" }
        require(!input.startsWith("//") && "://" !in input) { "absolute/network URLs are not allowed" }
        require(input.none { it == '\r' || it == '\n' || it == '\u0000' }) { "path contains control characters" }
        return input
    }

    /**
     * Retry transient hlpatch failures (timeouts / connection resets / malformed-once requests)
     * with linear backoff. Oversized-response errors fail fast: retrying cannot shrink the body.
     */
    private suspend fun getWithRetry(path: String, maxChars: Int, attempts: Int = 3): String {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return get(path, maxChars)
            } catch (e: Exception) {
                if (e.message?.contains("exceeded the") == true) throw e
                lastError = e
                if (attempt < attempts - 1) delay(250L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("hlpatch request failed: $path")
    }

    private suspend fun get(path: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val timeoutMs = umaSoReadTimeoutMs(path, maxChars)
        val connection = URL(base + normalizeSoUrl(path)).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val reader = stream?.bufferedReader(Charsets.UTF_8)
                ?: throw IllegalStateException("hlpatch returned HTTP $code without a body")
            reader.use {
                val out = StringBuilder(minOf(maxChars, 8_192))
                val chunk = CharArray(16 * 1024)
                while (true) {
                    val count = it.read(chunk)
                    if (count < 0) break
                    if (out.length + count > maxChars) throw IllegalStateException(
                        "hlpatch response exceeded the ${maxChars / 1024} KiB limit; use a bounded/range endpoint")
                    out.append(chunk, 0, count)
                }
                if (code !in 200..299) throw IllegalStateException("hlpatch HTTP $code: ${out.take(300)}")
                out.toString()
            }
        } catch (timeout: SocketTimeoutException) {
            throw IllegalStateException(
                "hlpatch read timed out after ${timeoutMs / 1000}s at $path; use a narrower search/range endpoint or retry",
                timeout,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name = name, description = description,
            parameters = ToolParameters(properties = properties, required = required)))

    private fun toolError(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()
    override fun handles(name: String): Boolean = name in names || sessionExportTools.handles(name)
}
