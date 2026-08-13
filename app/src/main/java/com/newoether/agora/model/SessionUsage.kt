package com.newoether.agora.model

import kotlinx.serialization.Serializable

@Serializable
enum class UsageOrigin { PROVIDER_REPORTED, LOCALLY_ESTIMATED }

@Serializable
data class UsageRequestRecord(
    val requestId: String,
    val conversationId: String,
    /** Stable identity. Custom providers use their normalized endpoint identity, not display name. */
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val inputTokensTotal: Int? = null,
    val inputTokensCached: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val totalTokens: Int? = null,
    val cacheDetailsStatus: CacheDetailsStatus = CacheDetailsStatus.NOT_PROVIDED,
    val origin: UsageOrigin,
    /** Original provider usage object, retained for provider-specific mappings and diagnostics. */
    val rawUsageJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val inputTokensUncached: Int?
        get() = if (inputTokensTotal != null && inputTokensCached != null) {
            (inputTokensTotal - inputTokensCached).coerceAtLeast(0)
        } else null
}

data class ConversationUsageSummary(
    val conversationId: String,
    val records: List<UsageRequestRecord>,
) {
    val reportedRecords = records.filter { it.origin == UsageOrigin.PROVIDER_REPORTED }
    val estimatedRecords = records.filter { it.origin == UsageOrigin.LOCALLY_ESTIMATED }
    val reportedInputTokens = reportedRecords.mapNotNull { it.inputTokensTotal }.sum()
    val reportedOutputTokens = reportedRecords.mapNotNull { it.outputTokens }.sum()
    val reportedReasoningTokens = reportedRecords.mapNotNull { it.reasoningTokens }.sum()
    val reportedCachedTokens = reportedRecords.mapNotNull { it.inputTokensCached }.sum()
    val reportedUncachedTokens = reportedRecords.mapNotNull { it.inputTokensUncached }.sum()
    val estimatedInputTokens = estimatedRecords.mapNotNull { it.inputTokensTotal }.sum()
    val estimatedOutputTokens = estimatedRecords.mapNotNull { it.outputTokens }.sum()
    val cacheDetailsComplete = reportedRecords.isNotEmpty() && reportedRecords.all {
        it.cacheDetailsStatus == CacheDetailsStatus.PROVIDED
    }
}
