package com.newoether.agora.api.anthropic

import com.newoether.agora.model.CacheDetailsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnthropicTokenUsageMapperTest {
    @Test
    fun mapsInputAndOutputWithoutInventingCacheDetails() {
        val usage = AnthropicUsage(inputTokens = 80, outputTokens = 20).toTokenUsage()

        assertEquals(80, usage.inputTokensTotal)
        assertEquals(20, usage.outputTokens)
        assertEquals(100, usage.totalTokens)
        assertEquals(CacheDetailsStatus.NOT_PROVIDED, usage.cacheDetailsStatus)
        assertNull(usage.inputTokensCached)
        assertNull(usage.inputTokensUncached)
        assertNull(usage.cacheReadTokens)
        assertNull(usage.cacheCreationTokens)
        assertNull(usage.thoughtsTokens)
    }

    @Test
    fun keepsTotalUnknownWhenOneSideIsMissing() {
        val usage = AnthropicUsage(inputTokens = 80).toTokenUsage()

        assertEquals(80, usage.inputTokensTotal)
        assertNull(usage.outputTokens)
        assertNull(usage.totalTokens)
    }
}
