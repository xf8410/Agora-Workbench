package com.newoether.agora.model

import com.newoether.agora.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bounds the size of a single persisted `messages` row so it can never exceed the platform
 * CursorWindow (~2MB) and trigger `SQLiteBlobTooBigException` / `Row too big to fit into
 * CursorWindow` (issue #51).
 *
 * Individual tool results are already clipped at capture time
 * ([Constants.MAX_TOOL_RESULT_LENGTH]), but a *model* message aggregates many tool rounds into a
 * single `toolCallJson` column — and the model answer `text` column is otherwise unbounded. This
 * guard bounds both: [clipText] caps a text column, and [encodeSegmentsBounded] encodes a segment
 * list while progressively trimming the largest stored fields until the encoded row fits the
 * budget.
 *
 * When trimming is needed, the largest tool-result (then, if still over, the largest content)
 * is halved with a truncation marker. Losing fidelity in the oldest/largest tool results is the
 * correct trade-off: they are already far back in the conversation (likely falling out of the
 * context window) and the alternative is a crash. The algorithm strictly reduces the largest
 * field each iteration and gives up once every field is at the floor, so it always terminates.
 */
object MessagePersistenceGuard {

    /** Floor below which a field is no longer trimmed (keeps a useful residual instead of a
     *  uselessly tiny one, and guarantees termination when a row has many small segments). */
    private const val TRIM_FLOOR_CHARS = 2000

    private const val TRUNCATION_MARKER = "\n…[truncated for persistence]"

    /** Trim a persisted text column to a safe length. Preserves the un-truncated text otherwise. */
    fun clipText(text: String): String =
        if (text.length <= Constants.MAX_PERSISTED_TEXT_CHARS) text
        else text.take(Constants.MAX_PERSISTED_TEXT_CHARS) + TRUNCATION_MARKER

    /**
     * Encode [segments] to JSON, bounded to [maxBytes] UTF-8 bytes. When the encoded form would
     * exceed the budget, the largest trimmable field (a tool result, then a non-tool content) is
     * halved with a marker and the list re-encoded, repeating until it fits or every field is at
     * the floor. Returns `null` for an empty list so the column stays SQL NULL (matching prior
     * behaviour where callers passed `null` for "no segments").
     */
    fun encodeSegmentsBounded(
        segments: List<MessageSegment>?,
        maxBytes: Int = Constants.MAX_PERSISTED_ROW_BYTES,
    ): String? {
        if (segments.isNullOrEmpty()) return null
        var current: List<MessageSegment> = segments
        while (true) {
            val json = Json.encodeToString(current)
            if (utf8Size(json) <= maxBytes) return json
            val pick = current.withIndex().maxByOrNull { (_, s) -> trimmableSize(s) } ?: return json
            val seg = pick.value
            if (!canTrim(seg)) return json // every field already at the floor; can't shrink further
            current = current.toMutableList().also { it[pick.index] = trimLargest(seg) }
        }
    }

    /** Size of the field that trimming would shrink — drives "largest first" selection. */
    private fun trimmableSize(s: MessageSegment): Int {
        val result = s.toolResult?.length ?: 0
        val content = if (s.type == "tool") 0 else s.content.length
        return maxOf(result, content)
    }

    private fun canTrim(s: MessageSegment): Boolean =
        (s.toolResult != null && s.toolResult.length > TRIM_FLOOR_CHARS) ||
            (s.type != "tool" && s.content.length > TRIM_FLOOR_CHARS)

    /** Halve the largest trimmable field of [s], preferring the tool result on ties. */
    private fun trimLargest(s: MessageSegment): MessageSegment {
        val result = s.toolResult
        if (result != null && result.length >= s.content.length && result.length > TRIM_FLOOR_CHARS) {
            return s.copy(toolResult = halveWithMarker(result))
        }
        if (s.type != "tool" && s.content.length > TRIM_FLOOR_CHARS) {
            return s.copy(content = halveWithMarker(s.content))
        }
        return s
    }

    private fun halveWithMarker(value: String): String {
        val target = maxOf(value.length / 2, TRIM_FLOOR_CHARS)
        return if (value.length <= target) value else value.take(target) + TRUNCATION_MARKER
    }

    private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
