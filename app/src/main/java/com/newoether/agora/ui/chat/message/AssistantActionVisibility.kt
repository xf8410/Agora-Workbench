package com.newoether.agora.ui.chat.message

/**
 * Keep the assistant action row available whenever a reply has copyable text.
 * A stale streaming flag must not remove Copy after durable answer text is visible.
 */
internal fun shouldShowAssistantActions(isStreaming: Boolean, text: String): Boolean =
    !isStreaming || text.isNotBlank()
