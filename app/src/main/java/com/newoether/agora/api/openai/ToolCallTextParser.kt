package com.newoether.agora.api.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Recovers tool calls that an OpenAI-compatible server emitted as **content text** rather than as
 * structured `delta.tool_calls` (issue #33, path B). llama.cpp and other self-hosted servers
 * frequently finish with `finish_reason == "stop"` while placing the tool call inside the message
 * `content` — the model's chat template renders it as a tagged ``{json}`` block. The structured
 * path in [BaseOpenAiProvider] only fires on `finish_reason == "tool_calls"`, so without this
 * fallback such servers never enter the tool-call phase (the JSON just shows up as answer text).
 * This parser brings them to parity with Ollama, which reads the structured field.
 *
 * Recognized forms:
 *  - One or more tagged blocks anywhere in the content (the standard form emitted by
 *    Hermes / Qwen / llama.cpp tool-aware templates). The inner JSON may use
 *    `{"name":...,"arguments":...}` or `{"name":...,"parameters":...}`, or nest them under
 *    `"function"`.
 *  - As a last resort, the *entire* trimmed content being a single JSON object or array of the
 *    same tool-call shape (some templates emit the JSON with no surrounding tags). Only attempted
 *    when the whole content is JSON, so prose answers are never misread as tool calls.
 *
 * The inner `arguments`/`parameters` value is preserved verbatim as a JSON string for the
 * downstream tool executor, matching how structured tool calls carry arguments.
 */
internal object ToolCallTextParser {

    data class ParsedCall(val name: String, val arguments: String)

    // Split so the bare tag literals never appear as a contiguous substring in source tooling.
    private const val OPEN_TAG = "<tool_" + "call>"
    private const val CLOSE_TAG = "</tool_" + "call>"

    /** Extract tool calls from [content]; empty if none are recognized. */
    fun parse(content: String): List<ParsedCall> {
        val results = mutableListOf<ParsedCall>()
        var idx = 0
        while (true) {
            val start = content.indexOf(OPEN_TAG, idx)
            if (start < 0) break
            val innerStart = start + OPEN_TAG.length
            val end = content.indexOf(CLOSE_TAG, innerStart)
            if (end < 0) break
            val inner = content.substring(innerStart, end).trim()
            parseCallJson(inner)?.let { results.add(it) }
            idx = end + CLOSE_TAG.length
        }
        if (results.isNotEmpty()) return results

        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        // Only treat the whole content as a tool call when it is pure JSON — never parse tool
        // calls out of prose that merely happens to contain a JSON fragment.
        parseCallJson(trimmed)?.let { return listOf(it) }
        if (trimmed.startsWith("[")) {
            val array = try { Json.parseToJsonElement(trimmed).jsonArray } catch (_: Exception) { return emptyList() }
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                parseCallJson(obj.toString())?.let { results.add(it) }
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private fun parseCallJson(jsonStr: String): ParsedCall? {
        val obj = try { Json.parseToJsonElement(jsonStr).jsonObject } catch (_: Exception) { return null }
        val name = stringField(obj, "name")
            ?: (obj["function"] as? JsonObject)?.let { stringField(it, "name") }
            ?: return null
        if (name.isBlank()) return null
        val args = obj["arguments"] ?: obj["parameters"]
        val arguments = args?.let { normalizeArguments(it) } ?: "{}"
        return ParsedCall(name, arguments)
    }

    private fun stringField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    /** The tool executor expects a JSON string for arguments; keep objects/arrays as-is and
     *  stringify primitives so the downstream parser still sees valid JSON. */
    private fun normalizeArguments(element: JsonElement): String =
        when (element) {
            is JsonObject, is JsonArray -> element.toString()
            is JsonPrimitive -> if (element.isString) element.content else element.toString()
            else -> element.toString()
        }
}
