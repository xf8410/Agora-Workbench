package com.newoether.agora.viewmodel

import com.newoether.agora.api.HttpClient

/**
 * Per-conversation collection of in-flight HTTP streaming handles. [cancelAll] severs only the
 * streams opened under this scope — so a Stop on conversation A no longer kills conversation B's
 * in-flight provider stream (the fix for the global `cancelAllStreams` race).
 *
 * Carries the owning [conversationId] so coroutine-local scope bindings (HttpClient stream
 * scope) can attribute side-channel bookkeeping — e.g. SessionUsageRuntime token-usage records —
 * to the right conversation without threading the id through every provider call.
 */
class StreamScope(val conversationId: String? = null) {
    private val handles = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<HttpClient.StreamHandle, Boolean>()
    )

    fun register(handle: HttpClient.StreamHandle) {
        handles.add(handle)
    }

    fun unregister(handle: HttpClient.StreamHandle) {
        handles.remove(handle)
    }

    fun cancelAll() {
        handles.toList().forEach { runCatching { it.cancel() } }
        handles.clear()
    }
}
