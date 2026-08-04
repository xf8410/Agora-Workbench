package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryPagingTest {
    private fun message(id: String, timestamp: Long) = MessageEntity(
        id = id,
        conversationId = "conversation",
        text = id,
        participant = Participant.USER,
        timestamp = timestamp,
    )

    @Test
    fun equalTimestampBoundary_isOrderedByIdWithoutLoss() {
        val current = listOf(message("c", 100), message("d", 101))
        val older = listOf(message("a", 99), message("b", 100))

        val merged = ConversationHistoryPaging.mergeOlder(current, older)

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun overlappingPages_areDeduplicatedWithoutOmissions() {
        val newest = listOf(message("e", 5), message("f", 6))
        val middle = listOf(message("c", 3), message("d", 4), message("e", 5))
        val oldest = listOf(message("a", 1), message("b", 2), message("c", 3))

        val merged = ConversationHistoryPaging.mergeOlder(
            ConversationHistoryPaging.mergeOlder(newest, middle),
            oldest,
        )

        assertEquals(listOf("a", "b", "c", "d", "e", "f"), merged.map { it.id })
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
    }

    @Test
    fun currentSnapshotWinsWhenDuplicateIdIsSeenAgain() {
        val current = listOf(message("same", 20).copy(text = "current"))
        val staleOlder = listOf(message("same", 10).copy(text = "stale"))

        val merged = ConversationHistoryPaging.mergeOlder(current, staleOlder)

        assertEquals(1, merged.size)
        assertEquals("current", merged.single().text)
        assertEquals(20, merged.single().timestamp)
    }

    @Test
    fun hasAnotherPage_onlyForFullPositivePage() {
        assertTrue(ConversationHistoryPaging.hasAnotherPage(24, 24))
        assertFalse(ConversationHistoryPaging.hasAnotherPage(23, 24))
        assertFalse(ConversationHistoryPaging.hasAnotherPage(0, 0))
    }
}
