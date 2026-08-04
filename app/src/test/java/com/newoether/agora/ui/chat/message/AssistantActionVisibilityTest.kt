package com.newoether.agora.ui.chat.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActionVisibilityTest {
    @Test
    fun completedReply_showsActions() {
        assertTrue(shouldShowAssistantActions(isStreaming = false, text = "answer"))
    }

    @Test
    fun staleStreamingFlag_withVisibleAnswer_stillShowsCopyActions() {
        assertTrue(shouldShowAssistantActions(isStreaming = true, text = "durable answer"))
    }

    @Test
    fun emptyStreamingPlaceholder_hidesActions() {
        assertFalse(shouldShowAssistantActions(isStreaming = true, text = ""))
    }
}
