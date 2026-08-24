package com.newoether.agora.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MessageSegmentsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeSegments(segments: List<MessageSegment>?): String? {
        if (segments.isNullOrEmpty()) return null
        return json.encodeToString(segments)
    }
}
