package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists terminal (STOPPED) message state to the DB after a generation is stopped. Kept separate
 * from per-conversation [ConversationGenerationState] (which owns no repos) so it can delegate
 * finalization without holding repository references.
 *
 * Runs on the supplied conversation-owned scope; the stopped conversation id comes from
 * [ConversationGenerationState.StopResult], NOT from the live `currentConversationId`, so a stop
 * triggered after the user switched conversations still persists to the ORIGINAL conversation.
 */
class GenerationFinalizer(
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val onIndexMessageForRag: (messageId: String, text: String) -> Unit,
) {
    /**
     * Persist [messages] as STOPPED into [conversationId] on [scope]. Returns the launched job
     * (or null if nothing to persist). The caller may chain a subsequent generation onto this job.
     */
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
                    ) {
                        onIndexMessageForRag(message.id, message.text)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Failed to persist stopped generation", e)
            }
        }
    }
}

private fun ChatMessage.toStoppedEntity(conversationId: String): MessageEntity {
    val toolJson = segments?.let { Json.encodeToString(it) } ?: toolCall?.let {
        Json.encodeToString(listOf(
            MessageSegment(
                type = "tool",
                toolName = it.toolName,
                toolArgs = it.arguments,
                toolResult = it.result,
                signature = it.signature,
                toolCallId = it.toolCallId,
            )
        ))
    }
    return MessageEntity(
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
    )
}
