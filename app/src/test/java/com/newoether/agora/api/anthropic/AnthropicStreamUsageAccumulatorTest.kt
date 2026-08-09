package com.newoether.agora.api.anthropic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnthropicStreamUsageAccumulatorTest {
    @Test
    fun combinesMessageStartInputWithMessageDeltaOutput() {
        val accumulator = AnthropicStreamUsageAccumulator()
        accumulator.recordMessageStart(AnthropicUsage(inputTokens = 90))

        val usage = accumulator.composeMessageDelta(AnthropicUsage(outputTokens = 30))

        assertEquals(90, usage.inputTokensTotal)
        assertEquals(30, usage.outputTokens)
        assertEquals(120, usage.totalTokens)
    }

    @Test
    fun doesNotInventInputWhenMessageStartHadNoUsage() {
        val usage = AnthropicStreamUsageAccumulator()
            .composeMessageDelta(AnthropicUsage(outputTokens = 30))

        assertNull(usage.inputTokensTotal)
        assertEquals(30, usage.outputTokens)
        assertNull(usage.totalTokens)
    }

    @Test
    fun acceptsInputFromDeltaWhenProviderIncludesItThere() {
        val usage = AnthropicStreamUsageAccumulator()
            .composeMessageDelta(AnthropicUsage(inputTokens = 90, outputTokens = 30))

        assertEquals(90, usage.inputTokensTotal)
        assertEquals(120, usage.totalTokens)
    }
}
