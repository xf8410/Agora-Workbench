package com.newoether.agora.api.openai

import com.newoether.agora.api.OpenAiCompletionTokensDetails
import com.newoether.agora.api.OpenAiPromptTokensDetails
import com.newoether.agora.api.OpenAiUsage
import com.newoether.agora.model.CacheDetailsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiTokenUsageMapperTest {
    @Test
    fun mapsCompleteUsageAndCacheDetails() {
        val usage = OpenAiUsage(
            promptTokens = 100,
            completionTokens = 20,
            totalTokens = 120,
            promptTokensDetails = OpenAiPromptTokensDetails(cachedTokens = 75),
            completionTokensDetails = OpenAiCompletionTokensDetails(reasoningTokens = 8),
        ).toTokenUsage()

        assertEquals(100, usage.inputTokensTotal)
        assertEquals(75, usage.inputTokensCached)
        assertEquals(25, usage.inputTokensUncached)
        assertEquals(20, usage.outputTokens)
        assertEquals(8, usage.thoughtsTokens)
        assertEquals(75, usage.cacheReadTokens)
        assertEquals(120, usage.totalTokens)
        assertEquals(CacheDetailsStatus.PROVIDED, usage.cacheDetailsStatus)
        assertEquals(0.75, usage.cacheHitRatio!!, 0.0)
    }

    @Test
    fun preservesMissingCacheAndReasoningDetailsAsUnknown() {
        val usage = OpenAiUsage(
            promptTokens = 40,
            completionTokens = 10,
            totalTokens = 50,
        ).toTokenUsage()

        assertEquals(40, usage.inputTokensTotal)
        assertEquals(10, usage.outputTokens)
        assertEquals(50, usage.totalTokens)
        assertEquals(CacheDetailsStatus.NOT_PROVIDED, usage.cacheDetailsStatus)
        assertNull(usage.inputTokensCached)
        assertNull(usage.inputTokensUncached)
        assertNull(usage.thoughtsTokens)
        assertNull(usage.cacheHitRatio)
    }
}
