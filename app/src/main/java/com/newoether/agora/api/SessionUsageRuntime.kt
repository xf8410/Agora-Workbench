package com.newoether.agora.api

import android.content.Context
import com.newoether.agora.data.SessionUsageStore
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.UsageRequestRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
            usage.origin, usage.rawUsageJson,
        ))
    }
    fun stableProviderId(providerName: String, baseUrl: String?): String {
        if (baseUrl.isNullOrBlank()) return "builtin:${providerName.lowercase()}"
        val normalized = runCatching {
            val uri = java.net.URI(baseUrl.trim().trimEnd('/'))
            "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}${if (uri.port >= 0) ":${uri.port}" else ""}${uri.path.trimEnd('/')}"
        }.getOrDefault(baseUrl.trim().lowercase().trimEnd('/'))
        return "endpoint:${normalized.hashCode().toUInt().toString(16)}"
    }
}
