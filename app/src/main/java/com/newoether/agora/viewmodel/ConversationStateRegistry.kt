package com.newoether.agora.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped registry of per-conversation generation state. Each conversation gets its own
 * [ConversationGenerationState] on first use; the entry is removed (and its scope cancelled) when
 * the conversation is deleted.
 *
 * This is the structural fix for the process-global single-slot generation state that caused
 * cross-conversation races (G2–G10): two conversations now hold independent `streamingMessage` /
 * `isLoading` / `generationJob` / `persistId` / `sendGate`, so a generation on conversation B
 * cannot clobber conversation A's UI mirror, skip A's DB persist, or get killed by A's Stop.
 *
 * The registry itself holds no generation logic — it only owns the lifecycle of the per-
 * conversation state objects. Generation entry points ([MessageGenerationController]) obtain a
 * state via [getOrCreate] and operate on it; ChatViewModel mirrors the currently-open
 * conversation's private flows into the global UI StateFlows.
 */
class ConversationStateRegistry {

    private val states = ConcurrentHashMap<String, ConversationGenerationState>()

    private val _activeConversationIds = MutableStateFlow<Set<String>>(emptySet())
    /** Conversation ids that currently have an active generation. Drives Stop-button visibility
     *  per conversation and the multi-conversation generating indicator. */
    val activeConversationIds: StateFlow<Set<String>> = _activeConversationIds.asStateFlow()

    /** Invoked once when a new ConversationGenerationState is created, so ChatViewModel can wire
     *  its onActive/onIdle hooks to markActive/markIdle. */
    @Volatile var onStateCreated: ((ConversationGenerationState) -> Unit)? = null

    fun getOrCreate(conversationId: String): ConversationGenerationState =
        states.computeIfAbsent(conversationId) {
            ConversationGenerationState(it).also { state -> onStateCreated?.invoke(state) }
        }

    fun get(conversationId: String): ConversationGenerationState? = states[conversationId]

    /** Mark a conversation as actively generating. */
    fun markActive(conversationId: String) {
        _activeConversationIds.update { it + conversationId }
    }

    /** Mark a conversation as no longer generating. */
    fun markIdle(conversationId: String) {
        _activeConversationIds.update { it - conversationId }
    }

    fun isActive(conversationId: String): Boolean = conversationId in _activeConversationIds.value

    /** Remove and cancel a conversation's state. Called when the conversation is deleted. */
    fun remove(conversationId: String) {
        states.remove(conversationId)?.cancelScope()
        markIdle(conversationId)
    }

    /** Stop a specific conversation's generation (only that one). Returns null if no state. */
    fun stop(conversationId: String): ConversationGenerationState.StopResult? {
        val state = states[conversationId] ?: return null
        return state.stop()
    }

    /** Cancel every conversation's state (e.g. on ViewModel cleared). */
    fun cancelAll() {
        states.values.forEach { it.cancelScope() }
        states.clear()
        _activeConversationIds.value = emptySet()
    }
}
