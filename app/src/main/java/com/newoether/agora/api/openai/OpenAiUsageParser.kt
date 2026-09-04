package com.newoether.agora.api.openai

import com.newoether.agora.model.CacheDetailsStatus
import com.newoether.agora.model.TokenUsage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lenient usage parser for OpenAI-compatible and common alternate field names. The untouched raw
 * object is retained so a provider-specific mapper can be added without losing historical data.
 */
internal object OpenAiUsageParser {
    fun parse(raw: JsonElement): TokenUsage? {
        val obj = raw as? JsonObject ?: return null
        fun value(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
            obj[name]?.jsonPrimitive?.intOrNull
        }
        fun nested(parent: String, vararg names: String): Int? =
            (obj[parent] as? JsonObject)?.let { child ->
                names.firstNotNullOfOrNull { child[it]?.jsonPrimitive?.intOrNull }
            }

        val input = value("prompt_tokens", "input_tokens", "promptTokenCount")
        val output = value("completion_tokens", "output_tokens", "candidatesTokenCount")
        val total = value("total_tokens", "totalTokenCount") ?: if (input != null && output != null) input + output else null
        val cached = nested("prompt_tokens_details", "cached_tokens")
            ?: nested("usage_details", "cached_tokens", "cache_read_input_tokens")
            ?: value("cache_read_input_tokens")
        val reasoning = nested("completion_tokens_details", "reasoning_tokens")
            ?: nested("usage_details", "reasoning_tokens")
            ?: value("reasoning_tokens", "thoughtsTokenCount")
        if (input == null && output == null && total == null && cached == null && reasoning == null) return null
        return TokenUsage(
            inputTokensTotal = input,
            inputTokensCached = cached,
            outputTokens = output,
            thoughtsTokens = reasoning,
            cacheReadTokens = cached,
            totalTokens = total,
            cacheDetailsStatus = if (cached != null) CacheDetailsStatus.PROVIDED else CacheDetailsStatus.NOT_PROVIDED,
            rawUsageJson = raw.toString(),
        )
    }
}
