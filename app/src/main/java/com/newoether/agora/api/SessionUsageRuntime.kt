package com.newoether.agora.api

import android.content.Context
import com.newoether.agora.data.SessionUsageStore
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.UsageRequestRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-scoped bridge from provider streams to the durable [SessionUsageStore].
 *
 * Providers run on arbitrary coroutines without access to the ViewModel layer, so this
 * runtime is installed once at app start (see AgoraApplication) and records every
 * [TokenUsage] the providers emit while a stream coroutine is bound to a conversation
 * via HttpClient's stream scope. Safe no-op when no scope is bound (e.g. fetchModels).
 */
object SessionUsageRuntime {
    @Volatile private var store: SessionUsageStore? = null
    private val empty = MutableStateFlow<List<UsageRequestRecord>>(emptyList())

    fun install(context: Context) {
        if (store == null) synchronized(this) {
            if (store == null) store = SessionUsageStore(context.applicationContext)
        }
    }

    fun records(): StateFlow<List<UsageRequestRecord>> = store?.records ?: empty

    internal fun record(requestId: String, providerId: String, providerName: String, modelId: String, usage: TokenUsage) {
        val conversationId = HttpClient.boundStreamScope()?.conversationId ?: return
        store?.upsert(UsageRequestRecord(
            requestId, conversationId, providerId, providerName, modelId,
            usage.inputTokensTotal, usage.inputTokensCached, usage.outputTokens,
            usage.thoughtsTokens, usage.totalTokens, usage.cacheDetailsStatus,
            com.newoether.agora.model.UsageOrigin.PROVIDER_REPORTED, usage.rawUsageJson,
        ))
    }

    /** Normalized endpoint identity so the same custom gateway stays one provider across renames. */
    fun stableProviderId(providerName: String, baseUrl: String?): String {
        if (baseUrl.isNullOrBlank()) return "builtin:${providerName.lowercase()}"
        val normalized = runCatching {
            val uri = java.net.URI(baseUrl.trim().trimEnd('/'))
            "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}${if (uri.port >= 0) ":${uri.port}" else ""}${uri.path.trimEnd('/')}"
        }.getOrDefault(baseUrl.trim().lowercase().trimEnd('/'))
        return "endpoint:${normalized.hashCode().toUInt().toString(16)}"
    }
}
