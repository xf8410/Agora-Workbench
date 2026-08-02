package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Durable full-fidelity payload store for message fields that are unsafe to keep inline in a Room
 * CursorWindow row. Room remains the lightweight index/UI source; model context can rehydrate the
 * complete payload on demand without repeating a tool call.
 */
class ConversationPayloadStore(context: Context) {
    private val root = File(context.filesDir, "conversation-payloads").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Envelope(
        val version: Int = 1,
        val conversationId: String,
        val messageId: String,
        val text: String,
        val thoughts: String? = null,
        val toolCallJson: String? = null,
        val attachmentMeta: String? = null,
        val sha256: String,
    )

    suspend fun preserve(entity: MessageEntity) = withContext(Dispatchers.IO) {
        runCatching {
            val digestInput = buildString {
                append(entity.text); append('\u0000')
                append(entity.thoughts.orEmpty()); append('\u0000')
                append(entity.toolCallJson.orEmpty()); append('\u0000')
                append(entity.attachmentMeta.orEmpty())
            }
            val envelope = Envelope(
                conversationId = entity.conversationId,
                messageId = entity.id,
                text = entity.text,
                thoughts = entity.thoughts,
                toolCallJson = entity.toolCallJson,
                attachmentMeta = entity.attachmentMeta,
                sha256 = sha256(digestInput),
            )
            val directory = conversationDir(entity.conversationId).apply { mkdirs() }
            val target = File(directory, safeName(entity.id) + ".json")
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeText(json.encodeToString(envelope), Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target)) { "Atomic payload rename failed" }
            }
        }.onFailure { DebugLog.e("PayloadStore", "Failed to preserve ${entity.id}", it) }.getOrThrow()
    }

    suspend fun rehydrate(entity: MessageEntity): MessageEntity = withContext(Dispatchers.IO) {
        val file = File(conversationDir(entity.conversationId), safeName(entity.id) + ".json")
        if (!file.isFile) return@withContext entity
        val envelope = runCatching { json.decodeFromString<Envelope>(file.readText(Charsets.UTF_8)) }
            .onFailure { DebugLog.e("PayloadStore", "Failed to read ${entity.id}", it) }
            .getOrNull() ?: return@withContext entity
        if (envelope.conversationId != entity.conversationId || envelope.messageId != entity.id) {
            return@withContext entity
        }
        val digestInput = buildString {
            append(envelope.text); append('\u0000')
            append(envelope.thoughts.orEmpty()); append('\u0000')
            append(envelope.toolCallJson.orEmpty()); append('\u0000')
            append(envelope.attachmentMeta.orEmpty())
        }
        if (sha256(digestInput) != envelope.sha256) {
            DebugLog.w("PayloadStore", "Payload checksum mismatch for ${entity.id}")
            return@withContext entity
        }
        entity.copy(
            text = envelope.text,
            thoughts = envelope.thoughts,
            toolCallJson = envelope.toolCallJson,
            attachmentMeta = envelope.attachmentMeta,
        )
    }

    suspend fun rehydrateAll(entities: List<MessageEntity>): List<MessageEntity> =
        withContext(Dispatchers.IO) { entities.map { rehydrate(it) } }

    suspend fun deleteMessage(conversationId: String, messageId: String) = withContext(Dispatchers.IO) {
        runCatching { File(conversationDir(conversationId), safeName(messageId) + ".json").delete() }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        runCatching { conversationDir(conversationId).deleteRecursively() }
    }

    private fun conversationDir(id: String) = File(root, safeName(id))
    private fun safeName(value: String): String = sha256(value).take(40)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
