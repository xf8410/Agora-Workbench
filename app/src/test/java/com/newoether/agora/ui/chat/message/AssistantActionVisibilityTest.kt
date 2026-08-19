package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantActionVisibilityTest {
    @Test fun completedTextReply_showsActions() = assertTrue(shouldShowAssistantActions(false, "answer", false))
    @Test fun staleStreamingFlag_withText_showsActions() = assertTrue(shouldShowAssistantActions(true, "answer", false))
    @Test fun terminalToolOrErrorReply_showsActions() = assertTrue(shouldShowAssistantActions(false, "", true))
    @Test fun emptyStreamingPlaceholder_hidesActions() = assertFalse(shouldShowAssistantActions(true, "", false))

    @Test fun terminalReportedUsage_showsTokens() {
        assertTrue(shouldShowAssistantTokenUsage(MessageStatus.SUCCESS, 42))
        assertTrue(shouldShowAssistantTokenUsage(MessageStatus.ERROR, 42))
        assertTrue(shouldShowAssistantTokenUsage(MessageStatus.STOPPED, 42))
    }

    @Test fun unknownOrNonTerminalUsage_hidesTokens() {
        assertFalse(shouldShowAssistantTokenUsage(MessageStatus.SUCCESS, 0))
        assertFalse(shouldShowAssistantTokenUsage(MessageStatus.THINKING, 42))
    }
}
