package com.newoether.agora.api.anthropic

import com.newoether.agora.model.TokenUsage

/**
 * Anthropic sends input usage at message_start and output usage at message_delta.
 * Keep the two observations separate until an output update can be emitted.
 */
internal class AnthropicStreamUsageAccumulator {
    private var inputTokens: Int? = null

    fun recordMessageStart(usage: AnthropicUsage?) {
        usage?.inputTokens?.let { inputTokens = it }
    }

    fun composeMessageDelta(usage: AnthropicUsage): TokenUsage = AnthropicUsage(
        inputTokens = inputTokens ?: usage.inputTokens,
        outputTokens = usage.outputTokens,
    ).toTokenUsage()
}
