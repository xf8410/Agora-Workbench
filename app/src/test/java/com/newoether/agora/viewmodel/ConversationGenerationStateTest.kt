package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationGenerationStateTest {

    @Test
    fun replacementClaim_isIdleOnlyAndAtomic() {
        val state = ConversationGenerationState("conversation")

        val token = state.tryAcquireForReplacement()

        assertTrue(token != null)
        assertTrue(state.generating.value)
        assertNull(state.tryAcquireForReplacement())
        assertTrue(state.endGeneration(token!!))
        assertFalse(state.generating.value)
    }

    @Test
    fun streamClear_commitsFinalMessageBeforeRemovingOverlay() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val finalMessage = ChatMessage(
            id = "model",
            text = "complete",
            participant = Participant.MODEL,
            status = MessageStatus.SUCCESS,
        )
        val events = mutableListOf<String>()
        state.onStreamCommit = { _, message ->
            assertEquals(finalMessage, state.streamingMessage.value)
            assertEquals(finalMessage, message)
            events += "commit"
        }
        state.streamUpdate(token, finalMessage)

        state.streamClear(token)

        events += "cleared"
        assertEquals(listOf("commit", "cleared"), events)
        assertNull(state.streamingMessage.value)
    }

    @Test
    fun streamClear_keepsStoppedOverlay() {
        val state = ConversationGenerationState("conversation")
        val token = state.acquireForSend()!!
        val stopped = ChatMessage(
            id = "model",
            text = "partial",
            participant = Participant.MODEL,
            status = MessageStatus.STOPPED,
        )
        state.streamUpdate(token, stopped)

        state.streamClear(token)

        assertEquals(stopped, state.streamingMessage.value)
    }
}
