package com.newoether.agora.api

import android.content.Context
import com.newoether.agora.data.SessionUsageStore
import com.newoether.agora.model.TokenUsage
import com.newoether.agora.model.UsageRequestRecord

/** Process bridge between provider streams and durable conversation usage storage. */
object SessionUsageRuntime {
    @Volatile private var store: SessionUsageStore? = null

    fun install(context: Context) {
        if (store == null) synchronized(this) {
            if (store == null) store = SessionUsageStore(context.applicationContext)
        }
    }

    fun records() = store?.records

    internal fun record(
        requestId: String,
        providerId: String,
        providerName: String,
        modelId: String,
        usage: TokenUsage,
    ) {
        val conversationId = HttpClient.boundStreamScope()?.conversationId ?: return
        store?.upsert(UsageRequestRecord(
            requestId = requestId,
            conversationId = conversationId,
            providerId = providerId,
            providerName = providerName,
            modelId = modelId,
            inputTokensTotal = usage.inputTokensTotal,
            inputTokensCached = usage.inputTokensCached,
            outputTokens = usage.outputTokens,
            reasoningTokens = usage.thoughtsTokens,
            totalTokens = usage.totalTokens,
            cacheDetailsStatus = usage.cacheDetailsStatus,
            origin = usage.origin,
            rawUsageJson = usage.rawUsageJson,
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
