package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.SelectedAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * One per conversation. Owns that conversation's private generation state — the IO scope,
 * current generation job, send gate, streaming/loading UI flows, ownership tokens, and the
 * queued-send list — so two conversations can generate in parallel without their state
 * clobbering each other.
 *
 * Replaces the process-global single-slot generation state that predated per-conversation parallelism.
 * The global StateFlows ChatViewModel exposes to the UI are now a mirror of whichever
 * conversation is currently open (see [ConversationStateRegistry]); background conversations
 * mutate only their own private flows here and write the DB, so they stay invisible until the
 * user switches back.
 *
 * ## Ownership tokens (unchanged semantics, scoped per conversation)
 *
 *  • [uiGenToken] owns the shared UI mirror (isLoading/streamingMessage/generatingInConversationId
 *    as seen through the registry). Advanced on EVERY stop and captured by each new generation.
 *    Token-gated mutators below only touch state while their captured token is current.
 *
 *  • [persistId] owns the model message's DB row. Advanced ONLY when a new generation starts
 *    (never on stop), so a stopped generation still persists its own text while a superseded
 *    one is blocked from clobbering the newer message.
 *
 * ## Slot lifecycle (acquireForSend / tryAcquireForReplacement / endGeneration / stop)
 *
 * The generation "slot" (the [generating] flag under [genLock]) is the single atomic decision
 * point for launch-vs-enqueue. [acquireForSend] claims it cooperatively (null → enqueue);
 * [tryAcquireForReplacement] claims it only while idle (regenerate/edit are disabled during an
 * active generation); [endGeneration] releases it token-gated when a generation ends; [stop] is
 * a terminal user Stop that fully releases it. Stop cancels ONLY this conversation's
 * [generationJob] and in-flight HTTP streams (via [streamScope]) — never another conversation's.
 */
class ConversationGenerationState(val conversationId: String) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var generationJob: Job? = null

    /** This conversation's in-flight HTTP streaming handles. Cancelled together on stop. */
    val streamScope: StreamScope = StreamScope()

    // ── Private UI state (mirrored to the global UI flows only while this conversation is open) ──
    val streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val isLoading = MutableStateFlow(false)
    /** True while this conversation has an active generation. Drives the Stop-button visibility. */
    val generating = MutableStateFlow(false)

    /** Queued sends waiting for the current generation to finish. Per-conversation. */
    val queuedSends = MutableStateFlow<List<QueuedSend>>(emptyList())

    // ── Ownership tokens ──
    private val genLock = Any()
    private var uiGenToken = 0L
    private val persistId = AtomicLong(0L)

    /** Captures the current UI-ownership token right after a stop, under the lock. */
    fun captureUiToken(): Long = synchronized(genLock) { uiGenToken }
    /** Claims DB-row ownership for a freshly-started generation. */
    fun nextPersistId(): Long = persistId.incrementAndGet()
    /** True while [persistId] still belongs to the generation that captured [id]. */
    fun isLatestPersist(id: Long): Boolean = persistId.get() == id
    /** True while [uiToken] is still the current UI-ownership token (nothing stopped/superseded us). */
    fun isCurrentToken(uiToken: Long): Boolean = synchronized(genLock) { uiGenToken == uiToken }

    // ── Generation slot (single source of truth: [generating] under [genLock]) ────────────
    // Replaces the old `sendGate` AtomicBoolean. The slot is the atomic decision point for
    // "launch now vs enqueue": exactly one generation owns a conversation's tree at a time.

    /**
     * Cooperative claim for a fresh send. If the slot is free, atomically marks this conversation
     * generating (advancing the UI token so any just-finished generation's late callbacks are gated
     * out), flips it active in the registry, and returns the captured token. If a generation is
     * already running, returns null → the caller must enqueue instead of launching (fixes the
     * silent-drop / same-conversation-parallel window: [generating] is now set synchronously here,
     * not deep inside the coroutine).
     */
    fun acquireForSend(): Long? = synchronized(genLock) {
        if (generating.value) return null
        uiGenToken += 1
        generating.value = true
        onActive?.invoke(conversationId)
        uiGenToken
    }

    /**
     * Atomic idle-only claim for regenerate/edit. The UI disables both actions while this
     * conversation is generating, but that visual gate can lag by a frame during a conversation
     * switch; enforcing the same rule here makes the state machine authoritative.
     */
    fun tryAcquireForReplacement(): Long? = synchronized(genLock) {
            if (generating.value) return null
            uiGenToken += 1
            isLoading.value = true
            generating.value = true
            onActive?.invoke(conversationId)
            uiGenToken
    }

    /**
     * Token-gated release of the slot when a generation coroutine finishes (or dies before
     * reaching [GenerationManager]'s tail). Only the still-current owner clears the slot, so a
     * superseded/stopped coroutine's finally is a no-op. Returns true if this call actually
     * released (i.e. the caller may now drain the queue).
     */
    fun endGeneration(uiToken: Long): Boolean = synchronized(genLock) {
        if (uiGenToken != uiToken) return false
        isLoading.value = false
        generating.value = false
        onIdle?.invoke(conversationId)
        true
    }

    // ── Token-gated UI mutators ───────────────────────────────────────────
    fun streamUpdate(uiToken: Long, msg: ChatMessage) {
        synchronized(genLock) { if (uiGenToken == uiToken) streamingMessage.value = msg }
    }
    fun loadingChange(uiToken: Long, value: Boolean) {
        synchronized(genLock) { if (uiGenToken == uiToken) isLoading.value = value }
    }
    fun streamClear(uiToken: Long) {
        synchronized(genLock) {
            if (uiGenToken != uiToken) return
            val message = streamingMessage.value
            // A user Stop deliberately keeps the STOPPED overlay until Room has persisted it.
            // Normal completion must commit the final in-memory message before removing the
            // overlay, otherwise the UI briefly falls back to the empty SENDING placeholder.
            if (message?.status != MessageStatus.STOPPED) {
                if (message != null) onStreamCommit?.invoke(conversationId, message)
                streamingMessage.value = null
            }
        }
    }

    /** Wired by ChatViewModel to mark this conversation active/idle in the registry and to commit
     * the final streaming message into the currently open conversation before overlay removal. */
    @Volatile var onActive: ((String) -> Unit)? = null
    @Volatile var onIdle: ((String) -> Unit)? = null
    @Volatile var onStreamCommit: ((String, ChatMessage) -> Unit)? = null

    /** Builds the token-gated callbacks for one generation, writing ONLY to this conversation's
     *  private state. The ChatViewModel mirror pipes private→global when this conversation is
     *  open, so the callbacks need no knowledge of the current conversation id. */
    fun callbacksFor(uiToken: Long, persistId: Long): GenerationCallbacks = GenerationCallbacks(
        onStreamUpdate = { streamUpdate(uiToken, it) },
        onLoadingChange = { loadingChange(uiToken, it) },
        onStreamClear = { streamClear(uiToken) },
        isLatestPersist = { isLatestPersist(persistId) },
    )

    // ── Stop / finalization ───────────────────────────────────────────────
    /**
     * Terminal stop that fully releases the slot (Stop button, or a delete that lands inside the
     * generating conversation). Cancels ONLY this conversation's job + in-flight HTTP streams,
     * advances the UI token, commits STOPPED to the streaming snapshot, and clears the slot
     * (generating=false + onIdle). Regenerate/edit never call Stop; they can claim only an idle
     * slot through [tryAcquireForReplacement].
     */
    fun stop(): StopResult {
        val previousJob = generationJob
        // Hard kill: cancel THIS conversation's in-flight HTTP streams only.
        streamScope.cancelAll()
        previousJob?.cancel()
        val stoppedMsg = synchronized(genLock) {
            uiGenToken += 1
            isLoading.value = false
            val s = streamingMessage.value?.copy(status = MessageStatus.STOPPED)
            streamingMessage.value = s
            generating.value = false
            onIdle?.invoke(conversationId)
            s
        }
        return StopResult(stoppedMsg, conversationId)
    }

    /** Cancel this conversation's scope (called when the conversation is deleted). */
    fun cancelScope() {
        scope.coroutineContext[Job]?.cancel()
    }

    /** Append a queued send (generation in progress → enqueue instead of launching). */
    fun enqueueSend(send: QueuedSend) {
        queuedSends.update { it + send }
    }

    /**
     * Atomically pop the head of the queue. Uses [getAndUpdate] so the returned item is derived
     * from the exact pre-update snapshot that won the CAS — no mutable side-effect var that a
     * retried update lambda could re-assign.
     */
    fun dequeueSend(): QueuedSend? =
        queuedSends.getAndUpdate { if (it.isEmpty()) it else it.drop(1) }.firstOrNull()

    /**
     * Remove a queued send by id (X button). Returns the removed item (or null) so the caller can
     * delete its now-orphaned attachment files — the composer already cleared its own reference on
     * enqueue, so the QueuedSend holds the only handle to those copied files.
     */
    fun removeQueuedSend(id: String): QueuedSend? {
        val before = queuedSends.getAndUpdate { queue -> queue.filterNot { it.id == id } }
        return before.firstOrNull { it.id == id }
    }

    /** Clear the whole queue, returning the removed items for orphan-file cleanup. */
    fun clearQueuedSends(): List<QueuedSend> = queuedSends.getAndUpdate { emptyList() }

    data class StopResult(val stoppedMessage: ChatMessage?, val conversationId: String)

}

/**
 * A message queued behind an in-progress generation, waiting to be sent. Carries the full
 * [SelectedAttachment] list (not bare paths) so a drained send rebuilds the exact same payload —
 * image/video frames, PDF pages, slice configs — as a direct send would. The composer copies each
 * attachment to app-private storage before enqueue and then clears its own list, so this list
 * holds the only live reference to those files until the send drains (ownership → MessageEntity)
 * or the item is removed (files deleted).
 */
data class QueuedSend(
    val id: String,
    val text: String,
    /** Model selected in the originating conversation when Send was tapped. */
    val modelId: String,
    val attachments: List<SelectedAttachment>,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Per-conversation collection of in-flight HTTP streaming handles. [cancelAll] severs only the
 * streams opened under this scope — so a Stop on conversation A no longer kills conversation B's
 * in-flight provider stream (the fix for the global `cancelAllStreams` race).
 */
class StreamScope {
    private val handles = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<com.newoether.agora.api.HttpClient.StreamHandle, Boolean>()
    )

    fun register(handle: com.newoether.agora.api.HttpClient.StreamHandle) {
        handles.add(handle)
    }

    fun unregister(handle: com.newoether.agora.api.HttpClient.StreamHandle) {
        handles.remove(handle)
    }

    fun cancelAll() {
        handles.toList().forEach { runCatching { it.cancel() } }
        handles.clear()
    }
}
