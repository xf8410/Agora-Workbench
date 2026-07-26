package com.newoether.agora.data.repository

import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.EmbeddingEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationRepository(
    private val chatDao: ChatDao
) {
    // ── Conversations ─────────────────────────────────────────

    private fun ChatEntity.toConversation() = ChatConversation(
        id = id, title = title, systemPromptId = systemPromptId, modelId = modelId,
        taskId = taskId, origin = origin, graduated = graduated
    )

    fun getAllConversations(): Flow<List<ChatConversation>> =
        chatDao.getAllConversations().map { entities -> entities.map { it.toConversation() } }

    fun observeConversation(id: String): Flow<ChatConversation?> =
        chatDao.observeConversation(id).map { it?.toConversation() }

    /** Executions spawned by [taskId], newest first — the task's execution log. */
    fun getExecutionsForTask(taskId: String): Flow<List<ChatConversation>> =
        chatDao.getExecutionsForTask(taskId).map { entities -> entities.map { it.toConversation() } }

    /** Observes message-level changes for every execution belonging to [taskId]. */
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>> =
        chatDao.observeExecutionMessagesForTask(taskId)

    /** Promotes a task/loop execution into the main list once the user takes it over.
     *  Returns true only for the transition that made the conversation searchable. */
    suspend fun graduateConversation(id: String): Boolean {
        val conv = chatDao.getConversation(id) ?: return false
        if (conv.origin != "user" && !conv.graduated) {
            chatDao.upsertConversation(conv.copy(graduated = true, lastUpdated = System.currentTimeMillis()))
            return true
        }
        return false
    }

    suspend fun getConversation(id: String): ChatEntity? =
        chatDao.getConversation(id)

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

    // ── Messages ──────────────────────────────────────────────

    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
        chatDao.getMessagesForConversation(conversationId)

    suspend fun getMessagesForConversationSnapshot(conversationId: String): List<MessageEntity> =
        chatDao.getMessagesForConversation(conversationId).first()

    suspend fun getLastMessageForConversation(conversationId: String): MessageEntity? =
        chatDao.getLastMessageForConversation(conversationId)

    suspend fun upsertMessage(entity: MessageEntity) = chatDao.upsertMessage(entity)

    suspend fun deleteMessagesByIds(ids: List<String>) = chatDao.deleteMessagesByIds(ids)

    suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity> =
        chatDao.getMessagesByIds(ids)

    suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity> =
        if (ids.isEmpty()) emptyList() else chatDao.getSearchableMessagesByIds(ids)

    suspend fun isMessageSearchable(messageId: String): Boolean =
        chatDao.isMessageSearchable(messageId)

    // ── Branch Selection ──────────────────────────────────────

    suspend fun saveBranchSelections(conversationId: String, selections: Map<String?, String>) {
        val conversation = chatDao.getConversation(conversationId) ?: return
        val stringKeyMap = selections.mapKeys { it.key ?: "null" }
        val json = Json.encodeToString(stringKeyMap)
        if (conversation.selectedBranchesJson != json) {
            chatDao.upsertConversation(conversation.copy(selectedBranchesJson = json, lastUpdated = System.currentTimeMillis()))
        }
    }

    suspend fun restoreBranchSelections(conversationId: String): Map<String?, String> {
        val conversation = chatDao.getConversation(conversationId) ?: return emptyMap()
        val raw = conversation.selectedBranchesJson ?: return emptyMap()
        return try {
            val map = Json.decodeFromString<Map<String, String>>(raw)
            map.mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── Stuck Message Fixer ───────────────────────────────────

    suspend fun fixStuckMessages(conversationId: String) {
        val stuckMessages = chatDao.getMessagesForConversation(conversationId).first()
            .filter {
                it.status == MessageStatus.SENDING ||
                it.status == MessageStatus.THINKING ||
                it.status == MessageStatus.TOOL_CALLING ||
                it.status == MessageStatus.TRANSCRIBING
            }
        stuckMessages.forEach { msg ->
            chatDao.upsertMessage(msg.copy(status = MessageStatus.STOPPED))
        }
    }

    // ── Embeddings ────────────────────────────────────────────

    suspend fun deleteEmbeddingsByConversation(conversationId: String) =
        chatDao.deleteEmbeddingsByConversation(conversationId)

    suspend fun deleteOrphanedEmbeddings() =
        chatDao.deleteOrphanedEmbeddings()

    suspend fun deleteEmbeddingsByModel(modelId: String) =
        chatDao.deleteEmbeddingsByModel(modelId)

    suspend fun getEmbeddedMessageIdsByModel(modelId: String): List<String> =
        chatDao.getEmbeddedMessageIdsByModel(modelId)

    suspend fun upsertEmbedding(entity: EmbeddingEntity) =
        chatDao.upsertEmbedding(entity)

    suspend fun upsertEmbeddingIfSearchable(entity: EmbeddingEntity): Boolean =
        chatDao.upsertEmbeddingIfSearchable(entity)

    suspend fun deleteAllConversations() =
        chatDao.deleteAllConversations()

    suspend fun findExistingMessageIds(ids: List<String>): List<String> =
        chatDao.findExistingMessageIds(ids)

    suspend fun getEmbeddingsByModel(modelId: String): List<EmbeddingEntity> =
        chatDao.getEmbeddingsByModel(modelId)

    suspend fun deleteEmbedding(messageId: String) =
        chatDao.deleteEmbedding(messageId)

    suspend fun getEmbeddingCountByModel(modelId: String): Int =
        chatDao.getEmbeddingCountByModel(modelId)

    suspend fun getIndexableMessageCount(): Int =
        chatDao.getIndexableMessageCount()

    // ── Search ────────────────────────────────────────────────

    suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity> =
        chatDao.searchMessages(query, limit)

    suspend fun getAllConversationsList(): List<ChatEntity> =
        chatDao.getAllConversationsList()

    suspend fun getSearchableConversation(id: String): ChatEntity? =
        chatDao.getSearchableConversation(id)

    suspend fun getSearchableConversationsList(): List<ChatEntity> =
        chatDao.getSearchableConversationsList()

    suspend fun getAllMessageImages() = chatDao.getAllMessageImages()

    suspend fun getAllMessagesList(): List<MessageEntity> =
        chatDao.getAllMessagesList()

    suspend fun getAllMessagesForIndexing(): List<MessageEntity> =
        chatDao.getAllMessagesForIndexing()

    /** Persists the composer draft (text + serialized attachments) for a conversation. */
    suspend fun updateDraft(conversationId: String, draftText: String, draftAttachments: String?) {
        chatDao.updateDraft(conversationId, draftText, draftAttachments)
    }

    /** Deletes all on-disk attachment files referenced by [messages]. Safe to call with
     *  an empty list. Errors per-file are swallowed so one bad path never aborts a delete. */
    suspend fun deleteMessageFiles(messages: List<MessageEntity>) = deleteAttachmentFilesFromEntities(messages)

    /** Overload for the in-memory [ChatMessage] form used by the VM's cascade-delete path. */
    fun deleteMessageFiles(messages: List<ChatMessage>) {
        for (msg in messages) {
            for (imagePath in msg.images) {
                runCatching { java.io.File(imagePath).delete() }
            }
            msg.attachmentMeta?.items?.forEach { item ->
                val uri = item.originalUri ?: return@forEach
                if ((item.type == "video" || item.type == "image" || item.type == "file") &&
                    uri.startsWith("file://")
                ) {
                    runCatching { java.io.File(uri.removePrefix("file://")).delete() }
                }
            }
        }
    }

    private fun deleteAttachmentFilesFromEntities(messages: List<MessageEntity>) {
        for (msg in messages) {
            for (imagePath in msg.images) {
                runCatching { java.io.File(imagePath).delete() }
            }
            if (msg.attachmentMeta != null) {
                runCatching {
                    val meta = Json.decodeFromString<AttachmentMeta>(msg.attachmentMeta)
                    for (item in meta.items) {
                        val uri = item.originalUri ?: continue
                        if ((item.type == "video" || item.type == "image" || item.type == "file") &&
                            uri.startsWith("file://")
                        ) {
                            runCatching { java.io.File(uri.removePrefix("file://")).delete() }
                        }
                    }
                }
            }
        }
    }
}
