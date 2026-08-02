package com.newoether.agora.model

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.util.Constants
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single persistence boundary for every messages-row write.
 *
 * CursorWindow applies to the complete projected row, not to each TEXT column independently.
 * Therefore text, thoughts, toolCallJson and attachmentMeta must share one UTF-8 budget. All write
 * paths (normal completion, Stop, user messages and tool rows) pass through [sanitize].
 */
object MessagePersistenceGuard {
    private const val TRIM_FLOOR_CHARS = 2_000
    private const val TRUNCATION_MARKER = "\n…[truncated for persistence]"
    private const val ROW_BUDGET_BYTES = 1_200_000
    private const val STRUCTURAL_RESERVE_BYTES = 64_000
    private const val ATTACHMENT_TEXT_CHARS = 24_000
    private const val ATTACHMENT_META_BUDGET_BYTES = 160_000

    private val json = Json { ignoreUnknownKeys = true }

    fun clipText(text: String): String = clipChars(text, Constants.MAX_PERSISTED_TEXT_CHARS)

    /** Sanitize the complete row. The returned entity always has a bounded combined payload. */
    fun sanitize(entity: MessageEntity): MessageEntity {
        var text = clipText(entity.text)
        var thoughts = entity.thoughts
        var toolJson = boundSegmentsJson(entity.toolCallJson, 520_000)
        var attachmentJson = boundAttachmentJson(entity.attachmentMeta)

        fun payloadBytes(): Int = utf8Size(text) + utf8Size(thoughts.orEmpty()) +
            utf8Size(toolJson.orEmpty()) + utf8Size(attachmentJson.orEmpty())

        val budget = ROW_BUDGET_BYTES - STRUCTURAL_RESERVE_BYTES
        while (payloadBytes() > budget) {
            val candidates = listOf(
                "tool" to utf8Size(toolJson.orEmpty()),
                "thoughts" to utf8Size(thoughts.orEmpty()),
                "text" to utf8Size(text),
                "attachment" to utf8Size(attachmentJson.orEmpty()),
            )
            when (candidates.maxByOrNull { it.second }?.first) {
                "tool" -> {
                    val decoded = toolJson?.let { runCatching { json.decodeFromString<List<MessageSegment>>(it) }.getOrNull() }
                    toolJson = if (!decoded.isNullOrEmpty()) {
                        encodeSegmentsBounded(decoded, maxOf(96_000, utf8Size(toolJson.orEmpty()) / 2))
                    } else null
                }
                "thoughts" -> thoughts = thoughts?.let(::halveWithMarker)
                "text" -> text = halveWithMarker(text)
                "attachment" -> attachmentJson = null
                else -> break
            }
            if (text.length <= TRIM_FLOOR_CHARS &&
                (thoughts == null || thoughts.length <= TRIM_FLOOR_CHARS) &&
                utf8Size(toolJson.orEmpty()) <= 96_000 && attachmentJson == null
            ) break
        }

        // Last-resort deterministic bound. This should only be reached for pathological UTF-8.
        if (payloadBytes() > budget) {
            toolJson = null
            attachmentJson = null
            thoughts = thoughts?.let { clipUtf8(it, 180_000) }
            text = clipUtf8(text, 700_000)
        }

        return entity.copy(
            text = text,
            thoughts = thoughts,
            toolCallJson = toolJson,
            attachmentMeta = attachmentJson,
        )
    }

    fun encodeSegmentsBounded(
        segments: List<MessageSegment>?,
        maxBytes: Int = 520_000,
    ): String? {
        if (segments.isNullOrEmpty()) return null
        var current = segments
        while (true) {
            val encoded = Json.encodeToString(current)
            if (utf8Size(encoded) <= maxBytes) return encoded
            val pick = current.withIndex().maxByOrNull { (_, segment) -> trimmableSize(segment) }
                ?: return null
            if (!canTrim(pick.value)) return null
            current = current.toMutableList().also { it[pick.index] = trimLargest(pick.value) }
        }
    }

    private fun boundSegmentsJson(raw: String?, maxBytes: Int): String? {
        if (raw.isNullOrBlank()) return null
        if (utf8Size(raw) <= maxBytes) return raw
        val segments = runCatching { json.decodeFromString<List<MessageSegment>>(raw) }.getOrNull()
            ?: return null
        return encodeSegmentsBounded(segments, maxBytes)
    }

    private fun boundAttachmentJson(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val meta = runCatching { json.decodeFromString<AttachmentMeta>(raw) }.getOrNull()
            ?: return null
        val bounded = meta.copy(items = meta.items.map { item ->
            item.copy(
                textContent = item.textContent?.let { clipChars(it, ATTACHMENT_TEXT_CHARS) },
                transcription = item.transcription?.let { clipChars(it, ATTACHMENT_TEXT_CHARS) },
            )
        })
        val encoded = Json.encodeToString(bounded)
        return encoded.takeIf { utf8Size(it) <= ATTACHMENT_META_BUDGET_BYTES }
            ?: Json.encodeToString(bounded.copy(items = bounded.items.map {
                it.copy(textContent = null, transcription = null)
            })).takeIf { utf8Size(it) <= ATTACHMENT_META_BUDGET_BYTES }
    }

    private fun trimmableSize(segment: MessageSegment): Int = maxOf(
        segment.toolResult?.length ?: 0,
        segment.toolArgs?.length ?: 0,
        if (segment.type == "tool") 0 else segment.content.length,
    )

    private fun canTrim(segment: MessageSegment): Boolean =
        (segment.toolResult?.length ?: 0) > TRIM_FLOOR_CHARS ||
            (segment.toolArgs?.length ?: 0) > TRIM_FLOOR_CHARS ||
            (segment.type != "tool" && segment.content.length > TRIM_FLOOR_CHARS)

    private fun trimLargest(segment: MessageSegment): MessageSegment {
        val fields = listOf(
            "result" to (segment.toolResult?.length ?: 0),
            "args" to (segment.toolArgs?.length ?: 0),
            "content" to if (segment.type == "tool") 0 else segment.content.length,
        )
        return when (fields.maxByOrNull { it.second }?.first) {
            "result" -> segment.copy(toolResult = segment.toolResult?.let(::halveWithMarker))
            "args" -> segment.copy(toolArgs = segment.toolArgs?.let(::halveWithMarker))
            "content" -> segment.copy(content = halveWithMarker(segment.content))
            else -> segment
        }
    }

    private fun clipChars(value: String, maxChars: Int): String =
        if (value.length <= maxChars) value else value.take(maxChars) + TRUNCATION_MARKER

    private fun halveWithMarker(value: String): String {
        if (value.length <= TRIM_FLOOR_CHARS) return value
        return value.take(maxOf(value.length / 2, TRIM_FLOOR_CHARS)) + TRUNCATION_MARKER
    }

    private fun clipUtf8(value: String, maxBytes: Int): String {
        if (utf8Size(value) <= maxBytes) return value
        var low = 0
        var high = value.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (utf8Size(value.take(mid)) <= maxBytes) low = mid else high = mid - 1
        }
        return value.take(low) + TRUNCATION_MARKER
    }

    private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size
}
