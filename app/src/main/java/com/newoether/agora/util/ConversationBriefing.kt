package com.newoether.agora.util

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant

/**
 * Builds a compact text briefing from messages that fell outside the configured
 * conversation history window. The briefing travels with the request inside the
 * system prompt, so older context survives without occupying message slots in
 * the per-provider history window trim.
 *
 * Newest entries are kept preferentially; older entries are dropped first when
 * the per-entry or total character budget is exceeded.
 */
object ConversationBriefing {
    private const val MAX_ENTRIES = 40
    private const val ENTRY_TEXT_LIMIT = 160
    private const val TOTAL_CHAR_LIMIT = 4000

    /** Returns null when there is nothing meaningful to brief. */
    fun build(messages: List<ChatMessage>): String? =
        buildFromPairs(messages.map { it.participant to it.text })

    internal fun buildFromPairs(pairs: List<Pair<Participant, String>>): String? {
        val lines = mutableListOf<String>()
        var total = 0
        for ((participant, rawText) in pairs.reversed()) {
            val role = when (participant) {
                Participant.USER -> "用户"
                Participant.MODEL -> "助手"
                else -> null
            } ?: continue
            val text = rawText.replace(Regex("\\s+"), " ").trim().take(ENTRY_TEXT_LIMIT)
            if (text.isEmpty()) continue
            val line = "$role：$text"
            if (total + line.length + 1 > TOTAL_CHAR_LIMIT || lines.size >= MAX_ENTRIES) break
            lines.add(line)
            total += line.length + 1
        }
        if (lines.isEmpty()) return null
        lines.reverse()
        return lines.joinToString("\n")
    }
}
