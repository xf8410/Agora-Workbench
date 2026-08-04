package com.newoether.agora.model

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.util.Constants
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shared persistence boundary which bounds the combined UTF-8 payload of a messages row. */
object MessagePersistenceGuard {
    private const val TRIM_FLOOR_CHARS = 2_000
    private const val TRUNCATION_MARKER = "\n…[truncated for persistence]"
    private const val ROW_BUDGET_BYTES = 1_200_000
    private const val STRUCTURAL_RESERVE_BYTES = 64_000
    private const val ATTACHMENT_TEXT_CHARS = 24_000
    private const val ATTACHMENT_META_BUDGET_BYTES = 160_000
    private val json = Json { ignoreUnknownKeys = true }

    fun clipText(text: String): String = clipChars(text, Constants.MAX_PERSISTED_TEXT_CHARS)

    fun sanitize(entity: MessageEntity): MessageEntity {
        var text = clipText(entity.text)
        var thoughts = entity.thoughts
        var toolJson = boundSegmentsJson(entity.toolCallJson, 520_000)
        var attachmentJson = boundAttachmentJson(entity.attachmentMeta)
        fun bytes() = utf8Size(text) + utf8Size(thoughts.orEmpty()) +
            utf8Size(toolJson.orEmpty()) + utf8Size(attachmentJson.orEmpty())
        val budget = ROW_BUDGET_BYTES - STRUCTURAL_RESERVE_BYTES
        while (bytes() > budget) {
            when (listOf("tool" to utf8Size(toolJson.orEmpty()), "thoughts" to utf8Size(thoughts.orEmpty()),
                "text" to utf8Size(text), "attachment" to utf8Size(attachmentJson.orEmpty())).maxByOrNull { it.second }?.first) {
                "tool" -> {
                    val decoded = toolJson?.let { runCatching { json.decodeFromString<List<MessageSegment>>(it) }.getOrNull() }
                    toolJson = if (!decoded.isNullOrEmpty()) encodeSegmentsBounded(decoded, maxOf(96_000, utf8Size(toolJson.orEmpty()) / 2)) else null
                }
                "thoughts" -> thoughts = thoughts?.let(::halveWithMarker)
                "text" -> text = halveWithMarker(text)
                "attachment" -> attachmentJson = null
                else -> break
            }
            if (text.length <= TRIM_FLOOR_CHARS && (thoughts == null || thoughts.length <= TRIM_FLOOR_CHARS) &&
                utf8Size(toolJson.orEmpty()) <= 96_000 && attachmentJson == null) break
        }
        if (bytes() > budget) {
            toolJson = null; attachmentJson = null
            thoughts = thoughts?.let { clipUtf8(it, 180_000) }
            text = clipUtf8(text, 700_000)
        }
        return entity.copy(text = text, thoughts = thoughts, toolCallJson = toolJson, attachmentMeta = attachmentJson)
    }

    fun encodeSegmentsBounded(segments: List<MessageSegment>?, maxBytes: Int = 520_000): String? {
        var current: List<MessageSegment> = segments?.takeIf { it.isNotEmpty() } ?: return null
        while (true) {
            val encoded = Json.encodeToString(current)
            if (utf8Size(encoded) <= maxBytes) return encoded
            val pick = current.withIndex().maxByOrNull { (_, s) -> trimmableSize(s) } ?: return null
            if (!canTrim(pick.value)) return null
            current = current.toMutableList().also { it[pick.index] = trimLargest(pick.value) }
        }
    }

    private fun boundSegmentsJson(raw: String?, maxBytes: Int): String? {
        if (raw.isNullOrBlank()) return null
        if (utf8Size(raw) <= maxBytes) return raw
        return runCatching { json.decodeFromString<List<MessageSegment>>(raw) }.getOrNull()
            ?.let { encodeSegmentsBounded(it, maxBytes) }
    }

    private fun boundAttachmentJson(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val meta = runCatching { json.decodeFromString<AttachmentMeta>(raw) }.getOrNull() ?: return null
        val bounded = meta.copy(items = meta.items.map { it.copy(
            textContent = it.textContent?.let { value -> clipChars(value, ATTACHMENT_TEXT_CHARS) },
            transcription = it.transcription?.let { value -> clipChars(value, ATTACHMENT_TEXT_CHARS) }) })
        val encoded = Json.encodeToString(bounded)
        return encoded.takeIf { utf8Size(it) <= ATTACHMENT_META_BUDGET_BYTES }
            ?: Json.encodeToString(bounded.copy(items = bounded.items.map { it.copy(textContent = null, transcription = null) }))
                .takeIf { utf8Size(it) <= ATTACHMENT_META_BUDGET_BYTES }
    }

    private fun trimmableSize(s: MessageSegment) = maxOf(s.toolResult?.length ?: 0, s.toolArgs?.length ?: 0,
        if (s.type == "tool") 0 else s.content.length)
    private fun canTrim(s: MessageSegment) = (s.toolResult?.length ?: 0) > TRIM_FLOOR_CHARS ||
        (s.toolArgs?.length ?: 0) > TRIM_FLOOR_CHARS || (s.type != "tool" && s.content.length > TRIM_FLOOR_CHARS)
    private fun trimLargest(s: MessageSegment): MessageSegment = when (listOf(
        "result" to (s.toolResult?.length ?: 0), "args" to (s.toolArgs?.length ?: 0),
        "content" to if (s.type == "tool") 0 else s.content.length).maxByOrNull { it.second }?.first) {
        "result" -> s.copy(toolResult = s.toolResult?.let(::halveWithMarker))
        "args" -> s.copy(toolArgs = s.toolArgs?.let(::halveWithMarker))
        "content" -> s.copy(content = halveWithMarker(s.content))
        else -> s
    }
    private fun clipChars(v: String, max: Int) = if (v.length <= max) v else v.take(max) + TRUNCATION_MARKER
    private fun halveWithMarker(v: String) = if (v.length <= TRIM_FLOOR_CHARS) v else v.take(maxOf(v.length / 2, TRIM_FLOOR_CHARS)) + TRUNCATION_MARKER
    private fun clipUtf8(v: String, max: Int): String {
        if (utf8Size(v) <= max) return v
        var low = 0; var high = v.length
        while (low < high) { val mid = (low + high + 1) ushr 1; if (utf8Size(v.take(mid)) <= max) low = mid else high = mid - 1 }
        return v.take(low) + TRUNCATION_MARKER
    }
    private fun utf8Size(v: String) = v.toByteArray(Charsets.UTF_8).size
}
