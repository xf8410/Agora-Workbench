package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage

/**
 * Publishes a newly-created user/model pair into the in-memory history atomically.
 *
 * Keyset history loading is snapshot based rather than a live Room collector. Therefore a send
 * must add BOTH persisted rows to the visible snapshot. Adding only the model placeholder leaves
 * its user parent absent; path resolution then treats the placeholder as a newer orphan component
 * and the preceding assistant reply appears to be replaced by the next send.
 */
internal object ConversationTurnAppend {
    fun append(
        current: List<ChatMessage>,
        userMessage: ChatMessage,
        modelPlaceholder: ChatMessage,
    ): List<ChatMessage> {
        val replacementIds = setOf(userMessage.id, modelPlaceholder.id)
        return (current.filterNot { it.id in replacementIds } + userMessage + modelPlaceholder)
            .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
    }
}
