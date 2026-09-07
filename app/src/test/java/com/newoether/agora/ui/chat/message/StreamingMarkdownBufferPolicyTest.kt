package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownBufferPolicyTest {

    @Test
    fun ordinaryStreamingMarkdown_keepsCrossfade() {
        assertTrue(shouldDoubleBufferStreamingMarkdown(8_192))
        assertTrue(shouldDoubleBufferStreamingMarkdown(STREAMING_MARKDOWN_DOUBLE_BUFFER_MAX_CHARS))
    }

    @Test
    fun veryLargeStreamingMarkdown_usesOneRenderTree() {
        assertFalse(shouldDoubleBufferStreamingMarkdown(STREAMING_MARKDOWN_DOUBLE_BUFFER_MAX_CHARS + 1))
        assertFalse(shouldDoubleBufferStreamingMarkdown(500_000))
    }
}
