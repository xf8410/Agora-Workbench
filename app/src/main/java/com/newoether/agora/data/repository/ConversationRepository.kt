package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessagePersistenceGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationRepository(private val chatDao: ChatDao) {
    private fun ChatEntity.toConversation() = ChatConversation(
        id = id, title = title, systemPromptId = systemPromptId, modelId = modelId,
        taskId = taskId, origin = origin, graduated = graduated
    )

    fun getAllConversations(): Flow<List<ChatConversation>> =
        chatDao.getAllConversations().map { list -> list.map { it.toConversation() } }
    fun observeConversation(id: String): Flow<ChatConversation?> =
        chatDao.observeConversation(id).map { it?.toConversation() }
    fun getExecutionsForTask(taskId: String): Flow<List<ChatConversation>> =
        chatDao.getExecutionsForTask(taskId).map { list -> list.map { it.toConversation() } }
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>> =
        chatDao.observeExecutionMessagesForTask(taskId)

    suspend fun graduateConversation(id: String): Boolean {
        val conv = chatDao.getConversation(id) ?: return false
        if (conv.origin != "user" && !conv.graduated) {
            chatDao.upsertConversation(conv.copy(graduated = true, lastUpdated = System.currentTimeMillis()))
            return true
        }
        return false
    }

    suspend fun getConversation(id: String) = chatDao.getConversation(id)
    suspend fun createConversation(title: String, systemPromptId: String? = null, modelId: String? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        chatDao.upsertConversation(ChatEntity(id = id, title = title, systemPromptId = systemPromptId, modelId = modelId))
        return id
    }
    suspend fun upsertConversation(entity: ChatEntity) = chatDao.upsertConversation(entity)

    suspend fun deleteConversation(id: String) {
        val messages = chatDao.getMessagesForConversation(id).first()
        deleteAttachmentFilesFromEntities(messages)
        chatDao.deleteEmbeddingsByConversation(id)
        chatDao.deleteMessagesByConversation(id)
        chatDao.deleteConversation(id)
    }

    fun getMessagesForConversation(conversationId: String, limit: Int = 100): Flow<List<MessageEntity>> =
        chatDao.getMessagesForConversation(conversationId, limit.coerceIn(1, 200))
    fun getMessageCountForConversation(conversationId: String): Flow<Int> =
        chatDao.getMessageCountForConversation(conversationId)

    /** Legacy full-tree operation. New model/UI paths must prefer ancestor/page queries. */
    suspend fun getMessagesForConversationSnapshot(conversationId: String): List<MessageEntity> =
        chatDao.getAllMessagesForConversation(conversationId)

    suspend fun getAncestorPath(conversationId: String, leafId: String?, maxDepth: Int = 200): List<MessageEntity> {
        if (leafId == null) return emptyList()
        val reverse = ArrayList<MessageEntity>(maxDepth.coerceAtMost(200))
        var id: String? = leafId
        var depth = 0
        while (id != null && depth++ < maxDepth.coerceIn(1, 500)) {
            val row = chatDao.getMessageInConversation(conversationId, id) ?: break
            reverse += row
            id = row.parentId
        }
        reverse.reverse()
        return reverse
    }

    suspend fun getLastMessageForConversation(conversationId: String) = chatDao.getLastMessageForConversation(conversationId)

    /** The one and only messages write boundary. */
    suspend fun upsertMessage(entity: MessageEntity) =
        chatDao.upsertMessage(MessagePersistenceGuard.sanitize(entity))

    suspend fun deleteMessagesByIds(ids: List<String>) = chatDao.deleteMessagesByIds(ids)
    suspend fun getMessagesByIds(ids: List<String>) = chatDao.getMessagesByIds(ids)
    suspend fun getSearchableMessagesByIds(ids: List<String>) =
        if (ids.isEmpty()) emptyList() else chatDao.getSearchableMessagesByIds(ids)
    suspend fun isMessageSearchable(messageId: String) = chatDao.isMessageSearchable(messageId)

    suspend fun saveBranchSelections(conversationId: String, selections: Map<String?, String>) {
        val conversation = chatDao.getConversation(conversationId) ?: return
        val encoded = Json.encodeToString(selections.mapKeys { it.key ?: "null" })
        if (conversation.selectedBranchesJson != encoded) {
            chatDao.upsertConversation(conversation.copy(selectedBranchesJson = encoded, lastUpdated = System.currentTimeMillis()))
        }
    }

    suspend fun restoreBranchSelections(conversationId: String): Map<String?, String> {
        val raw = chatDao.getConversation(conversationId)?.selectedBranchesJson ?: return emptyMap()
        return runCatching { Json.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap()).mapKeys { if (it.key == "null") null else it.key }
    }

    suspend fun fixStuckMessages(conversationId: String) = chatDao.stopStuckMessages(conversationId)
    suspend fun deleteEmbeddingsByConversation(conversationId: String) = chatDao.deleteEmbeddingsByConversation(conversationId)
    suspend fun deleteOrphanedEmbeddings() = chatDao.deleteOrphanedEmbeddings()
    suspend fun deleteEmbeddingsByModel(modelId: String) = chatDao.deleteEmbeddingsByModel(modelId)
    suspend fun getEmbeddedMessageIdsByModel(modelId: String) = chatDao.getEmbeddedMessageIdsByModel(modelId)
    suspend fun upsertEmbedding(entity: EmbeddingEntity) = chatDao.upsertEmbedding(entity)
    suspend fun upsertEmbeddingIfSearchable(entity: EmbeddingEntity) = chatDao.upsertEmbeddingIfSearchable(entity)
    suspend fun deleteAllConversations() = chatDao.deleteAllConversations()
    suspend fun findExistingMessageIds(ids: List<String>) = chatDao.findExistingMessageIds(ids)
    suspend fun getEmbeddingsByModel(modelId: String) = chatDao.getEmbeddingsByModel(modelId)
    suspend fun deleteEmbedding(messageId: String) = chatDao.deleteEmbedding(messageId)
    suspend fun getEmbeddingCountByModel(modelId: String) = chatDao.getEmbeddingCountByModel(modelId)
    suspend fun getIndexableMessageCount() = chatDao.getIndexableMessageCount()
    suspend fun searchMessages(query: String, limit: Int = 10) = chatDao.searchMessages(query, limit)
    suspend fun getAllConversationsList() = chatDao.getAllConversationsList()
    suspend fun getSearchableConversation(id: String) = chatDao.getSearchableConversation(id)
    suspend fun getSearchableConversationsList() = chatDao.getSearchableConversationsList()
    suspend fun getAllMessageImages() = chatDao.getAllMessageImages()
    suspend fun getMessagesPage(limit: Int = 100, offset: Int = 0) = chatDao.getMessagesPage(limit.coerceIn(1, 200), offset.coerceAtLeast(0))
    suspend fun getMessagesForIndexingPage(limit: Int = 100, offset: Int = 0) = chatDao.getMessagesForIndexingPage(limit.coerceIn(1, 200), offset.coerceAtLeast(0))
    suspend fun getUnembeddedMessagesPage(modelId: String, limit: Int = 200) = chatDao.getUnembeddedMessagesPage(modelId, limit.coerceIn(1, 200))
    suspend fun updateDraft(conversationId: String, draftText: String, draftAttachments: String?) = chatDao.updateDraft(conversationId, draftText, draftAttachments)

    suspend fun deleteMessageFiles(messages: List<MessageEntity>) = deleteAttachmentFilesFromEntities(messages)
    fun deleteMessageFiles(messages: List<ChatMessage>) {
        messages.forEach { msg ->
            msg.images.forEach { runCatching { java.io.File(it).delete() } }
            msg.attachmentMeta?.items?.forEach { item ->
                val uri = item.originalUri ?: return@forEach
                if (item.type in setOf("video", "image", "file") && uri.startsWith("file://"))
                    runCatching { java.io.File(uri.removePrefix("file://")).delete() }
            }
        }
    }

    private fun deleteAttachmentFilesFromEntities(messages: List<MessageEntity>) {
        messages.forEach { msg ->
            msg.images.forEach { runCatching { java.io.File(it).delete() } }
            msg.attachmentMeta?.let { raw ->
                runCatching { Json.decodeFromString<AttachmentMeta>(raw) }.getOrNull()?.items?.forEach { item ->
                    val uri = item.originalUri ?: return@forEach
                    if (item.type in setOf("video", "image", "file") && uri.startsWith("file://"))
                        runCatching { java.io.File(uri.removePrefix("file://")).delete() }
                }
            }
        }
    }
}
