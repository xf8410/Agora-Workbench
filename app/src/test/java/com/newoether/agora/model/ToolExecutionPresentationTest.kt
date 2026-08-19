package com.newoether.agora.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionPresentationTest {
    @Test fun `timestamps produce real positive duration`() {
        val segment = MessageSegment(
            type = "tool",
            toolStartedAtMs = 1_000L,
            toolFinishedAtMs = 3_450L,
        )
        assertEquals(2_450L, ToolExecutionPresentation.durationMs(segment))
    }

    @Test fun `missing or zero timing is not displayed as zero seconds`() {
        assertNull(ToolExecutionPresentation.durationMs(MessageSegment(type = "tool")))
        assertNull(ToolExecutionPresentation.durationMs(MessageSegment(type = "tool", durationMs = 0L)))
        assertNull(ToolExecutionPresentation.durationMs(MessageSegment(
            type = "tool",
            toolStartedAtMs = 5_000L,
            toolFinishedAtMs = 5_000L,
        )))
    }

    @Test fun `legacy completed result remains readable as success`() {
        val segment = MessageSegment(type = "tool", toolResult = "{\"ok\":true}")
        assertEquals(
            ToolExecutionStatus.SUCCESS,
            ToolExecutionPresentation.status(segment, MessageStatus.SUCCESS),
        )
    }

    @Test fun `timeout and failures retain distinct terminal states`() {
        val timeout = MessageSegment(type = "tool", toolResult = "[工具错误 T004] 工具执行超时")
        val failure = MessageSegment(type = "tool", toolResult = "{\"ok\":false,\"error\":\"bad\"}")
        assertEquals(ToolExecutionStatus.TIMED_OUT, ToolExecutionPresentation.status(timeout, MessageStatus.SUCCESS))
        assertEquals(ToolExecutionStatus.FAILED, ToolExecutionPresentation.status(failure, MessageStatus.SUCCESS))
        assertTrue(ToolExecutionPresentation.isTerminal(ToolExecutionStatus.TIMED_OUT))
    }

    @Test fun `abandoned legacy running tool is interrupted after restart`() {
        val segment = MessageSegment(type = "tool", toolResult = null)
        assertEquals(ToolExecutionStatus.RUNNING, ToolExecutionPresentation.status(segment, MessageStatus.TOOL_CALLING))
        assertEquals(ToolExecutionStatus.INTERRUPTED, ToolExecutionPresentation.status(segment, MessageStatus.SUCCESS))
        assertFalse(ToolExecutionPresentation.isTerminal(ToolExecutionStatus.RUNNING))
    }
}
