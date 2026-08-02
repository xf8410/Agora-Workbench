package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.MessageEntity

/** Bounded compatibility query used while the schema migrates away from full-tree SELECT *. */
internal suspend fun ChatDao.getMessageInConversation(
    conversationId: String,
    messageId: String,
): MessageEntity? = getMessagesByIds(listOf(messageId)).firstOrNull {
    it.conversationId == conversationId
}
