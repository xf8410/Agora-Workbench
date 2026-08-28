package com.newoether.agora.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionErrorsTest {

    @Test
    fun `fallback keeps raw error detail`() {
        val raw = """{"ok": false, "error": "unexpected end of stream on https://api.github.com"}"""
        val normalized = ToolExecutionErrors.normalizeResult("github_write_file", raw)
        assertTrue(normalized.contains("T099"))
        assertTrue(normalized.contains("unexpected end of stream"))
    }

    @Test
    fun `unclassified exception text is preserved`() {
        val normalized = ToolExecutionErrors.exception("some_tool", IllegalStateException("weird state xyz"))
        assertTrue(normalized.contains("T099"))
        assertTrue(normalized.contains("weird state xyz"))
    }

    @Test
    fun `known classifications still take precedence`() {
        val normalized = ToolExecutionErrors.exception("t", IllegalStateException("HTTP 403: forbidden"))
        assertTrue(normalized.contains("T008"))
    }
}
