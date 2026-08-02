package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists STOPPED state to the original conversation through the shared bounded write path. */
class GenerationFinalizer(
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val onIndexMessageForRag: (messageId: String, text: String) -> Unit,
) {
    fun launchStopFinalization(
        scope: CoroutineScope,
        conversationId: String?,
        messages: List<ChatMessage>,
    ): Job? {
        if (conversationId == null) return null
        val distinct = messages.distinctBy { it.id }
        if (distinct.isEmpty()) return null
        return scope.launch {
            try {
                if (convRepo.getConversation(conversationId) == null) return@launch
                for (message in distinct) {
                    convRepo.upsertMessage(message.toStoppedEntity(conversationId))
                    if (message.text.isNotBlank() && settings.autoCacheEnabled.value &&
                        (settings.modelSearchMethod.value == Constants.SEARCH_METHOD_RAG ||
                            settings.manualSearchMethod.value == Constants.SEARCH_METHOD_RAG)
                    ) onIndexMessageForRag(message.id, message.text)
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to persist stopped generation", e)
            }
        }
    }
}

private fun ChatMessage.toStoppedEntity(conversationId: String): MessageEntity {
    val toolJson = segments?.let { MessagePersistenceGuard.encodeSegmentsBounded(it) } ?: toolCall?.let {
        MessagePersistenceGuard.encodeSegmentsBounded(listOf(
            MessageSegment(
                type = "tool", toolName = it.toolName, toolArgs = it.arguments,
                toolResult = it.result, signature = it.signature, toolCallId = it.toolCallId,
            )
        ))
    }
    return MessagePersistenceGuard.sanitize(MessageEntity(
        id = id,
        conversationId = conversationId,
        parentId = parentId,
        text = text,
        images = images,
        thoughts = thoughts,
        thoughtTitle = thoughtTitle,
        tokenCount = tokenCount,
        status = MessageStatus.STOPPED,
        participant = participant,
        timestamp = timestamp,
        thoughtTimeMs = thoughtTimeMs,
        modelName = modelName,
        toolCallJson = toolJson,
        attachmentMeta = attachmentMeta?.let { Json.encodeToString(it) },
    ))
}
