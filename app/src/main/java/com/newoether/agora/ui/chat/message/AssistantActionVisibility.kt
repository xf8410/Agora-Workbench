package com.newoether.agora.ui.chat.message

import com.newoether.agora.model.MessageStatus

internal fun shouldShowAssistantActions(
    isStreaming: Boolean,
    text: String,
    hasRenderableContent: Boolean,
): Boolean = text.isNotBlank() || (!isStreaming && hasRenderableContent)

internal fun shouldShowAssistantTokenUsage(status: MessageStatus, tokenCount: Int): Boolean =
    status in setOf(MessageStatus.SUCCESS, MessageStatus.ERROR, MessageStatus.STOPPED) && tokenCount > 0
