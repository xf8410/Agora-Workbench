package com.newoether.agora.api.openai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamTerminationTest {
    @Test
    fun `finish reason stop closes stream`() {
        assertTrue(isTerminalOpenAiSseLine("data: {\"choices\":[{\"finish_reason\":\"stop\"}]}"))
    }

    @Test
    fun `finish reason tool calls closes stream after line delivery`() {
        assertTrue(isTerminalOpenAiSseLine("data: { \"choices\": [{ \"finish_reason\": \"tool_calls\" }] }"))
    }

    @Test
    fun `null finish reason keeps stream open`() {
        assertFalse(isTerminalOpenAiSseLine("data: {\"choices\":[{\"finish_reason\":null}]}"))
    }

    @Test
    fun `ordinary content and done markers are not intercepted`() {
        assertFalse(isTerminalOpenAiSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"done\"}}]}"))
        assertFalse(isTerminalOpenAiSseLine("data: [DONE]"))
    }
}
