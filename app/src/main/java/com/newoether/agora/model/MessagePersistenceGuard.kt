package com.newoether.agora.model

import com.newoether.agora.data.local.MessageEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Pass-through persistence boundary. No truncation, clipping, or filtering. */
object MessagePersistenceGuard {
    private val json = Json { ignoreUnknownKeys = true }

    fun clipText(text: String): String = text

    fun sanitize(entity: MessageEntity): MessageEntity = entity

    fun encodeSegmentsBounded(segments: List<MessageSegment>?, maxBytes: Int = 520_000): String? {
        if (segments.isNullOrEmpty()) return null
        return Json.encodeToString(segments)
    }
}
