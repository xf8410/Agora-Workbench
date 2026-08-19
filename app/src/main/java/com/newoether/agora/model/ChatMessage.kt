package com.newoether.agora.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ToolCallData(
    val toolName: String,
    val arguments: String,
    val result: String,
    val signature: String? = null,
    val toolCallId: String? = null
)

@Serializable
enum class ToolExecutionStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    TIMED_OUT,
    STOPPED,
    INTERRUPTED,
}

@Serializable
data class MessageSegment(
    val type: String, // "answer", "thought", "tool", or "transcription"
    val content: String = "",
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val toolCallId: String? = null,
    val signature: String? = null,
    val durationMs: Long? = null,
    /** Epoch millis captured immediately before tool execution. Absent on legacy messages. */
    val toolStartedAtMs: Long? = null,
    /** Epoch millis captured when tool execution reaches a terminal state. Absent on legacy messages. */
    val toolFinishedAtMs: Long? = null,
    /** Explicit terminal/running state. Null keeps old serialized segments backward compatible. */
    val toolStatus: ToolExecutionStatus? = null,
    /** True when list rendering intentionally omitted the full tool payload. */
    val payloadDeferred: Boolean = false,
)

object ToolCallDisplayModes {
    const val TIMELINE = "timeline"
    const val GROUPED_TIMELINE = "grouped_timeline"
    const val COMPACT = "compact"
    const val DEFAULT = GROUPED_TIMELINE

    fun normalize(value: String?): String = when (value) {
        COMPACT -> COMPACT
        GROUPED_TIMELINE -> GROUPED_TIMELINE
        TIMELINE -> TIMELINE
        else -> DEFAULT
    }
}

enum class Participant {
    USER, MODEL, ERROR
}

enum class MessageStatus {
    TRANSCRIBING, SENDING, THINKING, TOOL_CALLING, SUCCESS, STOPPED, ERROR
}

@Immutable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    val status: MessageStatus = MessageStatus.SUCCESS, // Default to SUCCESS for old messages
    val participant: Participant,
    val timestamp: Long = System.currentTimeMillis(),
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCall: ToolCallData? = null,
    val segments: List<MessageSegment>? = null,
    val attachmentMeta: AttachmentMeta? = null,
    val retryText: String? = null
)

@Immutable
data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val systemPromptId: String? = null,
    val modelId: String? = null,
    /** Set when this conversation is a task execution; drives the "from task" banner. */
    val taskId: String? = null,
    val origin: String = "user",
    val graduated: Boolean = false
)

@Immutable
data class StableMessageList(val list: List<ChatMessage> = emptyList())

@Immutable
data class StableModelAliases(val map: Map<String, String> = emptyMap())
