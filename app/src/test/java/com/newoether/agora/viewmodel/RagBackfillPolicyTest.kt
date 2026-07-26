package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Test

class RagBackfillPolicyTest {
    @Test
    fun backfillSelectsOnlyOrdinaryNonBlankUserAndModelMessages() {
        val eligible = messagesEligibleForRagBackfill(
            listOf(
                message("user", "question", Participant.USER),
                message("model", "answer", Participant.MODEL),
                message("error", "failed", Participant.ERROR),
                message("tool_call", "internal", Participant.MODEL),
                message("result_call", "internal", Participant.USER),
                message("blank", "  ", Participant.MODEL),
            )
        )

        assertEquals(listOf("user", "model"), eligible.map { it.id })
    }

    private fun message(id: String, text: String, participant: Participant) = MessageEntity(
        id = id,
        conversationId = "conversation",
        text = text,
        participant = participant,
        timestamp = 1L,
    )
}
