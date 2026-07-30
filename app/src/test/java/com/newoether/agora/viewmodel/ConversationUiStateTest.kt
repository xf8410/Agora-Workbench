package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUiStateTest {
    private var time = 1L

    private fun msg(
        id: String,
        parentId: String? = null,
        text: String = "text",
        participant: Participant = Participant.USER,
        timestamp: Long = time++,
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = participant,
        timestamp = timestamp,
    )

    @Test
    fun emptyState_returnsEmptyPath() {
        assertTrue(ConversationUiState.resolvePath(emptyList(), null, emptyMap()).isEmpty())
    }

    @Test
    fun linearConversation_returnsAllMessages() {
        val messages = listOf(
            msg("u1", text = "q1"),
            msg("m1", "u1", "a1", Participant.MODEL),
            msg("u2", "m1", "q2"),
            msg("m2", "u2", "a2", Participant.MODEL),
        )
        assertEquals(listOf("u1", "m1", "u2", "m2"),
            ConversationUiState.resolvePath(messages, null, emptyMap()).map { it.id })
    }

    @Test
    fun branchSelection_followsSelectedChild() {
        val messages = listOf(
            msg("u1"),
            msg("m1a", "u1", participant = Participant.MODEL),
            msg("m1b", "u1", participant = Participant.MODEL),
        )
        assertEquals("m1a",
            ConversationUiState.resolvePath(messages, null, mapOf("u1" to "m1a"))[1].id)
    }

    @Test
    fun branchSelection_defaultsToLast() {
        val messages = listOf(
            msg("u1"),
            msg("m1a", "u1", participant = Participant.MODEL),
            msg("m1b", "u1", participant = Participant.MODEL),
        )
        assertEquals("m1b",
            ConversationUiState.resolvePath(messages, null, emptyMap())[1].id)
    }

    @Test
    fun syntheticToolMessages_areHidden() {
        val messages = listOf(
            msg("u1"),
            msg("m1", "u1", participant = Participant.MODEL),
            msg(Constants.TOOL_MSG_PREFIX + "t1", "m1", participant = Participant.MODEL),
            msg(Constants.RESULT_MSG_PREFIX + "r1", Constants.TOOL_MSG_PREFIX + "t1", participant = Participant.MODEL),
        )
        assertEquals(listOf("u1", "m1"),
            ConversationUiState.resolvePath(messages, null, emptyMap()).map { it.id })
    }

    @Test
    fun boundedWindow_withoutRoot_startsAtLoadedVisibleComponent() {
        val messages = listOf(
            msg("u50", "missing-parent", "q50"),
            msg("m50", "u50", "a50", Participant.MODEL),
            msg("u51", "m50", "q51"),
        )
        assertEquals(listOf("u50", "m50", "u51"),
            ConversationUiState.resolvePath(messages, null, emptyMap()).map { it.id })
    }

    @Test
    fun boundedWindow_withOlderSyntheticComponent_doesNotRenderBlank() {
        val tool = Constants.TOOL_MSG_PREFIX + "old"
        val result = Constants.RESULT_MSG_PREFIX + "old"
        val messages = listOf(
            msg(tool, "missing-old-parent", timestamp = 10),
            msg(result, tool, timestamp = 11),
            msg("u50", "missing-active-parent", "visible question", timestamp = 20),
            msg("m50", "u50", "visible answer", Participant.MODEL, timestamp = 21),
        )
        assertEquals(listOf("u50", "m50"),
            ConversationUiState.resolvePath(messages, null, emptyMap()).map { it.id })
    }

    @Test
    fun boundedWindow_choosesNewestVisibleDisconnectedComponent() {
        val messages = listOf(
            msg("old-u", "missing-1", timestamp = 10),
            msg("old-m", "old-u", participant = Participant.MODEL, timestamp = 11),
            msg("new-u", "missing-2", timestamp = 20),
            msg("new-m", "new-u", participant = Participant.MODEL, timestamp = 21),
        )
        assertEquals(listOf("new-u", "new-m"),
            ConversationUiState.resolvePath(messages, null, emptyMap()).map { it.id })
    }

    @Test
    fun streamingMessage_substitutesMatchingId() {
        val messages = listOf(
            msg("u1"),
            msg("m1", "u1", "old", Participant.MODEL),
        )
        val streaming = ChatMessage(
            id = "m1",
            parentId = "u1",
            text = "updated",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
        )
        val path = ConversationUiState.resolvePath(messages, streaming, emptyMap())
        assertEquals("updated", path[1].text)
        assertEquals(MessageStatus.SENDING, path[1].status)
    }

    @Test
    fun streamingMessage_isAppendedBeforeRoomInsert() {
        val messages = listOf(msg("u1"))
        val streaming = ChatMessage(
            id = "m1",
            parentId = "u1",
            text = "new response",
            participant = Participant.MODEL,
            status = MessageStatus.SENDING,
        )
        assertEquals(listOf("u1", "m1"),
            ConversationUiState.resolvePath(messages, streaming, emptyMap()).map { it.id })
    }
}
