package com.newoether.agora.api.anthropic

import com.newoether.agora.model.CacheDetailsStatus
import com.newoether.agora.model.TokenUsage

/** Map the usage fields currently supplied by the Anthropic stream DTO without inventing details. */
internal fun AnthropicUsage.toTokenUsage(): TokenUsage = TokenUsage(
    inputTokensTotal = inputTokens,
    outputTokens = outputTokens,
    totalTokens = if (inputTokens != null && outputTokens != null) {
        inputTokens + outputTokens
    } else {
        null
    },
    cacheDetailsStatus = CacheDetailsStatus.NOT_PROVIDED,
)
