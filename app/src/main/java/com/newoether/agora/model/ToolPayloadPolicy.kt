package com.newoether.agora.model

/** Policy for keeping large tool JSON out of the normal paged chat query. */
object ToolPayloadPolicy {
    const val MAX_INLINE_JSON_CHARS: Int = 4_096

    fun shouldDefer(jsonLength: Int?): Boolean =
        jsonLength != null && jsonLength > MAX_INLINE_JSON_CHARS

    fun deferredSegments(): List<MessageSegment> = listOf(
        MessageSegment(type = "tool", payloadDeferred = true)
    )
}
