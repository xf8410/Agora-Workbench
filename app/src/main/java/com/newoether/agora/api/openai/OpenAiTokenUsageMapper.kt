package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiUsage
import com.newoether.agora.model.CacheDetailsStatus
import com.newoether.agora.model.TokenUsage

/** Preserve every usage field supplied by an OpenAI-compatible response. */
internal fun OpenAiUsage.toTokenUsage(): TokenUsage {
    val cachedTokens = promptTokensDetails?.cachedTokens
    return TokenUsage(
        inputTokensTotal = promptTokens,
        inputTokensCached = cachedTokens,
        outputTokens = completionTokens,
        thoughtsTokens = completionTokensDetails?.reasoningTokens,
        cacheReadTokens = cachedTokens,
        totalTokens = totalTokens,
        cacheDetailsStatus = if (cachedTokens != null) {
            CacheDetailsStatus.PROVIDED
        } else {
            CacheDetailsStatus.NOT_PROVIDED
        },
    )
}
