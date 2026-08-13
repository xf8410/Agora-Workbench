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

class ConversationGenerationState(val conversationId: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var generationJob: Job? = null
    val streamScope = StreamScope(conversationId)
    val streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val isLoading = MutableStateFlow(false)
    val generating = MutableStateFlow(false)
    val queuedSends = MutableStateFlow<List<QueuedSend>>(emptyList())
    private val genLock = Any()
    private var uiGenToken = 0L
    private val persistId = AtomicLong(0L)

    fun captureUiToken(): Long = synchronized(genLock) { uiGenToken }
    fun nextPersistId(): Long = persistId.incrementAndGet()
    fun isLatestPersist(id: Long) = persistId.get() == id
    fun isCurrentToken(token: Long) = synchronized(genLock) { uiGenToken == token }
    fun acquireForSend(): Long? = synchronized(genLock) {
        if (generating.value) return null
        uiGenToken++; generating.value = true; onActive?.invoke(conversationId); uiGenToken
    }
    fun tryAcquireForReplacement(): Long? = synchronized(genLock) {
        if (generating.value) return null
        uiGenToken++; isLoading.value = true; generating.value = true; onActive?.invoke(conversationId); uiGenToken
    }
    fun endGeneration(token: Long): Boolean = synchronized(genLock) {
        if (uiGenToken != token) return false
        isLoading.value = false; generating.value = false; onIdle?.invoke(conversationId); true
    }
    fun streamUpdate(token: Long, msg: ChatMessage) { synchronized(genLock) { if (uiGenToken == token) streamingMessage.value = msg } }
    fun loadingChange(token: Long, value: Boolean) { synchronized(genLock) { if (uiGenToken == token) isLoading.value = value } }
    fun streamClear(token: Long) { synchronized(genLock) {
        if (uiGenToken != token) return
        val message = streamingMessage.value
        if (message?.status != MessageStatus.STOPPED) {
            if (message != null) onStreamCommit?.invoke(conversationId, message)
            streamingMessage.value = null
        }
    } }
    @Volatile var onActive: ((String) -> Unit)? = null
    @Volatile var onIdle: ((String) -> Unit)? = null
    @Volatile var onStreamCommit: ((String, ChatMessage) -> Unit)? = null
    fun callbacksFor(token: Long, persist: Long) = GenerationCallbacks(
        onStreamUpdate = { streamUpdate(token, it) }, onLoadingChange = { loadingChange(token, it) },
        onStreamClear = { streamClear(token) }, isLatestPersist = { isLatestPersist(persist) })
    fun stop(): StopResult {
        streamScope.cancelAll(); generationJob?.cancel()
        val stopped = synchronized(genLock) {
            uiGenToken++; isLoading.value = false
            val value = streamingMessage.value?.copy(status = MessageStatus.STOPPED)
            streamingMessage.value = value; generating.value = false; onIdle?.invoke(conversationId); value
        }
        return StopResult(stopped, conversationId)
    }
    fun cancelScope() { scope.coroutineContext[Job]?.cancel() }
    fun enqueueSend(send: QueuedSend) { queuedSends.update { it + send } }
    fun dequeueSend(): QueuedSend? = queuedSends.getAndUpdate { if (it.isEmpty()) it else it.drop(1) }.firstOrNull()
    fun removeQueuedSend(id: String): QueuedSend? {
        val before = queuedSends.getAndUpdate { it.filterNot { send -> send.id == id } }
        return before.firstOrNull { it.id == id }
    }
    fun clearQueuedSends(): List<QueuedSend> = queuedSends.getAndUpdate { emptyList() }
    data class StopResult(val stoppedMessage: ChatMessage?, val conversationId: String)
}

data class QueuedSend(val id: String, val text: String, val modelId: String, val attachments: List<SelectedAttachment>, val createdAt: Long = System.currentTimeMillis())

class StreamScope(val conversationId: String = "") {
    private val handles = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<com.newoether.agora.api.HttpClient.StreamHandle, Boolean>())
    fun register(handle: com.newoether.agora.api.HttpClient.StreamHandle) { handles.add(handle) }
    fun unregister(handle: com.newoether.agora.api.HttpClient.StreamHandle) { handles.remove(handle) }
    fun cancelAll() { handles.toList().forEach { runCatching { it.cancel() } }; handles.clear() }
}
