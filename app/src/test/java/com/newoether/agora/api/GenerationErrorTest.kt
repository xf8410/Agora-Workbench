package com.newoether.agora.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationErrorTest {
    @Test fun `401 explains api key in Chinese`() {
        val message = GenerationError.Network(401, "Unauthorized").userMessage()
        assertTrue(message.contains("API 密钥"))
        assertFalse(message.contains("Authentication failed"))
    }

    @Test fun `429 explains frequency or quota in Chinese`() {
        val message = GenerationError.Network(429, "Rate limited").userMessage()
        assertTrue(message.contains("频繁") || message.contains("额度"))
    }

    @Test fun `502 is not automatically called a context overflow`() {
        val message = GenerationError.Network(502, "Bad gateway").userMessage()
        assertTrue(message.contains("502"))
        assertFalse(message.contains("消息上限"))
    }

    @Test fun `context overflow gives a concrete recovery action`() {
        val message = GenerationError.Api("400", "invalid_request_error", "maximum context length exceeded").userMessage()
        assertTrue(message.contains("消息上限"))
        assertTrue(message.contains("新建对话"))
    }

    @Test fun `model not found is localized without raw provider text`() {
        val message = GenerationError.Api("model_not_found", null, "The model does not exist").userMessage()
        assertTrue(message.contains("找不到所选模型"))
        assertFalse(message.contains("does not exist"))
    }

    @Test fun `tool failure does not expose raw English message`() {
        val message = GenerationError.ToolExecution("web_search", "{}", "API key missing").userMessage()
        assertTrue(message.contains("工具“web_search”执行失败"))
        assertFalse(message.contains("API key missing"))
    }

    @Test fun `timeout is Chinese and actionable`() {
        val message = GenerationError.Timeout.userMessage()
        assertTrue(message.contains("超时"))
        assertTrue(message.contains("重试"))
    }

    @Test fun `unknown English exception is replaced by Chinese fallback`() {
        val message = GenerationError.Unknown(RuntimeException("Boom!")).userMessage()
        assertTrue(message.contains("未知错误"))
        assertFalse(message.contains("Boom"))
    }

    @Test fun `connection refused is explained in Chinese`() {
        val message = GenerationError.Unknown(RuntimeException("Connection refused")).userMessage()
        assertTrue(message.contains("拒绝连接"))
    }

    @Test fun `cancelled is Chinese`() {
        assertTrue(GenerationError.Cancelled.userMessage().contains("停止"))
    }
}
