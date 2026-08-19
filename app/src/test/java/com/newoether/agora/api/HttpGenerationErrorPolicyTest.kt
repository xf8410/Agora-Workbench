package com.newoether.agora.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpGenerationErrorPolicyTest {
    @Test
    fun `plain 502 gateway failure is retryable and is not context overflow`() {
        val body = "upstream connect error or disconnect reset before headers"
        assertFalse(HttpGenerationErrorPolicy.isContextOverflow(body))
        assertNull(HttpGenerationErrorPolicy.contextErrorOrNull(502, body))
        assertTrue(HttpGenerationErrorPolicy.shouldRetry(502, body))
        assertTrue(GenerationError.Network(502, body).userMessage().contains("502"))
    }

    @Test
    fun `502 with explicit provider context evidence is classified from body`() {
        val body = "maximum context length is 131072 tokens; your request has 140000 tokens"
        assertTrue(HttpGenerationErrorPolicy.isContextOverflow(body))
        assertTrue(HttpGenerationErrorPolicy.contextErrorOrNull(502, body) is GenerationError.ContextWindow)
        assertFalse(HttpGenerationErrorPolicy.shouldRetry(502, body))
        assertTrue(GenerationError.Network(502, body).userMessage().contains("502"))
    }

    @Test
    fun `ordinary 400 context response is detected without requiring status 502`() {
        val body = "input is too long for the model token limit"
        assertTrue(HttpGenerationErrorPolicy.isContextOverflow(body))
        assertTrue(HttpGenerationErrorPolicy.contextErrorOrNull(400, body) is GenerationError.ContextWindow)
        assertFalse(HttpGenerationErrorPolicy.shouldRetry(400, body))
    }

    @Test
    fun `unrelated token wording is not treated as context overflow`() {
        val body = "invalid authentication token"
        assertFalse(HttpGenerationErrorPolicy.isContextOverflow(body))
        assertNull(HttpGenerationErrorPolicy.contextErrorOrNull(401, body))
    }
}
