package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTurnAppendTest {
    private fun message(
        id: String,
        parentId: String?,
        participant: Participant,
        timestamp: Long,
        text: String = id,
        status: MessageStatus = MessageStatus.SUCCESS,
    ) = ChatMessage(
        id = id,
        parentId = parentId,
        text = text,
        participant = participant,
        timestamp = timestamp,
        status = status,
    )

    @Test
    fun nextSend_keepsCompletedAssistantInVisibleLinearPath() {
        val firstUser = message("u1", null, Participant.USER, 1)
        val completedReply = message("m1", "u1", Participant.MODEL, 2, "completed reply")
        val nextUser = message("u2", "m1", Participant.USER, 3, "next question")
        val nextPlaceholder = message(
            "m2", "u2", Participant.MODEL, 4, "", MessageStatus.SENDING
        )

        val updated = ConversationTurnAppend.append(
            listOf(firstUser, completedReply), nextUser, nextPlaceholder
        )
        val path = ConversationUiState.resolvePath(updated, nextPlaceholder, emptyMap())

        assertEquals(listOf("u1", "m1", "u2", "m2"), path.map { it.id })
        assertTrue(path.any { it.id == "m1" && it.text == "completed reply" })
    }

    @Test
    fun retryingSameIds_replacesInsteadOfDuplicating() {
        val oldUser = message("u2", "m1", Participant.USER, 3, "old")
        val oldModel = message("m2", "u2", Participant.MODEL, 4, "old")
        val newUser = oldUser.copy(text = "new")
        val newModel = oldModel.copy(text = "")

        val updated = ConversationTurnAppend.append(
            listOf(oldUser, oldModel), newUser, newModel
        )

        assertEquals(2, updated.size)
        assertEquals("new", updated.single { it.id == "u2" }.text)
    }
}
