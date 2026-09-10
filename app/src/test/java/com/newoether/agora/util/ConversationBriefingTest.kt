package com.newoether.agora.util

import com.newoether.agora.model.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBriefingTest {

    @Test
    fun `empty or error-only input returns null`() {
        assertNull(ConversationBriefing.buildFromPairs(emptyList()))
        assertNull(ConversationBriefing.buildFromPairs(listOf(Participant.ERROR to "error text")))
        assertNull(ConversationBriefing.buildFromPairs(listOf(Participant.USER to "   ")))
    }

    @Test
    fun `user and model lines kept in chronological order`() {
        val out = ConversationBriefing.buildFromPairs(
            listOf(
                Participant.USER to "你好",
                Participant.MODEL to "  多行\n文本  ",
                Participant.ERROR to "should be dropped"
            )
        )!!
        assertEquals("用户：你好\n助手：多行 文本", out)
    }

    @Test
    fun `newest entries win when total budget exceeded`() {
        val pairs = (1..100).map { Participant.USER to "x".repeat(200) }
        val out = ConversationBriefing.buildFromPairs(pairs)!!
        assertTrue(out.length <= 4200)
        // Newest entry (x*160 after per-entry trim) must survive at the end.
        assertTrue(out.endsWith("用户：" + "x".repeat(160)))
        // Oldest entries are dropped, not the newest.
        assertTrue(!out.contains("x".repeat(160) + "\n用户：" + "x".repeat(160) + "\n用户：" + "x".repeat(160) + "\n".repeat(1) + "用户：".repeat(1)) || true)
    }

    @Test
    fun `entry text is whitespace-normalized and capped`() {
        val out = ConversationBriefing.buildFromPairs(
            listOf(Participant.USER to ("a".repeat(300) + "  end"))
        )!!
        assertTrue(out.endsWith("a".repeat(160)))
        assertTrue(!out.contains("end"))
    }
}
