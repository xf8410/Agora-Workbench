package com.newoether.agora.model

/**
 * Parser for multi-agent relay replies.
 *
 * A relay message is persisted as ONE assistant message whose [ChatMessage.modelName]
 * is `接力:名字1+名字2` (set by MessageGenerationController.runAgentRelay) and whose body
 * concatenates each teammate's contribution under a `【名字】` header line, optionally
 * followed by a `[接力失败] reason` note when a teammate failed mid-relay.
 *
 * The UI uses this parser to render each section as its own agent bubble instead of one
 * flat markdown blob. Parsing is intentionally forgiving: anything that does not match
 * the relay shape yields an empty [Parsed.sections] with a null error so the caller can
 * fall back to the regular markdown renderer.
 */
object RelaySections {

    /** [ChatMessage.modelName] prefix that marks a message as multi-agent relay output. */
    const val RELAY_MODEL_PREFIX = "接力:"

    /** Trailing failure note appended by [com.newoether.agora.viewmodel.MessageGenerationController]. */
    const val FAILURE_MARKER = "[接力失败]"

    /** One teammate contribution: the agent display name and its trimmed body. */
    data class Section(val agentName: String, val content: String)

    /**
     * Parse result. [errorText] carries the relay failure reason when present.
     * [hasRenderableContent] is false when the text is not relay-shaped, telling the
     * caller to fall back to the plain markdown body renderer.
     */
    data class Parsed(val sections: List<Section>, val errorText: String?) {
        val hasRenderableContent: Boolean
            get() = sections.isNotEmpty() || errorText != null
    }

    fun isRelayModelName(modelName: String?): Boolean =
        modelName != null && modelName.startsWith(RELAY_MODEL_PREFIX)

    private val SECTION_HEADER = Regex("^【(.+)】$")

    /**
     * Splits a relay body into per-agent sections. Lines matching `【name】` start a new
     * section; everything up to the next header (or the failure marker) belongs to it.
     * Text before the first header is ignored — relay bodies always start with a header.
     */
    fun parse(text: String): Parsed {
        var body = text
        var errorText: String? = null

        val markerIndex = body.indexOf(FAILURE_MARKER)
        if (markerIndex >= 0) {
            errorText = body.substring(markerIndex + FAILURE_MARKER.length).trim().ifBlank { null }
            body = body.substring(0, markerIndex)
        }

        if (body.isBlank()) {
            // Failure-only message (or blank body): nothing to section, but keep the reason
            // renderable. A blank body with no error means "not relay-shaped".
            return Parsed(emptyList(), errorText ?: body.takeIf { false })
        }

        val sections = mutableListOf<Section>()
        var currentName: String? = null
        val current = StringBuilder()
        fun flush() {
            val name = currentName
            val content = current.toString().trim()
            if (name != null && content.isNotEmpty()) sections.add(Section(name, content))
            currentName = null
            current.setLength(0)
        }

        body.lines().forEach { rawLine ->
            val line = rawLine.trim()
            val match = SECTION_HEADER.matchEntire(line)
            if (match != null) {
                flush()
                currentName = match.groupValues[1].trim()
            } else if (currentName != null) {
                current.appendLine(rawLine)
            }
        }
        flush()

        return Parsed(sections, errorText)
    }
}
