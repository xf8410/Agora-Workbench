package com.newoether.agora.model

/** Whether the provider supplied an input-cache breakdown for this request. */
enum class CacheDetailsStatus {
    PROVIDED,
    NOT_PROVIDED,
}

/**
 * Provider-neutral token usage for one model request.
 *
 * Nullable fields mean "the provider did not supply this value". They must not be
 * converted to zero, because zero usage and unavailable usage are different facts.
 */
data class TokenUsage(
    val inputTokensTotal: Int? = null,
    val inputTokensCached: Int? = null,
    val outputTokens: Int? = null,
    val thoughtsTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheCreationTokens: Int? = null,
    val totalTokens: Int? = null,
    val cacheDetailsStatus: CacheDetailsStatus = CacheDetailsStatus.NOT_PROVIDED,
) {
    init {
        require(inputTokensTotal == null || inputTokensTotal >= 0)
        require(inputTokensCached == null || inputTokensCached >= 0)
        require(outputTokens == null || outputTokens >= 0)
        require(thoughtsTokens == null || thoughtsTokens >= 0)
        require(cacheReadTokens == null || cacheReadTokens >= 0)
        require(cacheCreationTokens == null || cacheCreationTokens >= 0)
        require(totalTokens == null || totalTokens >= 0)
        require(inputTokensTotal == null || inputTokensCached == null || inputTokensCached <= inputTokensTotal)
        require(cacheDetailsStatus == CacheDetailsStatus.PROVIDED ||
            (inputTokensCached == null && cacheReadTokens == null && cacheCreationTokens == null))
    }

    /** Uncached input is knowable only when both total input and cached input are supplied. */
    val inputTokensUncached: Int?
        get() = if (inputTokensTotal != null && inputTokensCached != null) {
            inputTokensTotal - inputTokensCached
        } else null

    /** Cache hit ratio is unavailable when the provider omitted cache details or input is zero. */
    val cacheHitRatio: Double?
        get() = if (cacheDetailsStatus == CacheDetailsStatus.PROVIDED &&
            inputTokensTotal != null && inputTokensCached != null && inputTokensTotal > 0
        ) {
            inputTokensCached.toDouble() / inputTokensTotal.toDouble()
        } else null

    companion object {
        /** Compatibility shape for providers that currently expose only a total and thoughts count. */
        fun fromTotalOnly(totalTokens: Int, thoughtsTokens: Int = 0): TokenUsage = TokenUsage(
            totalTokens = totalTokens.takeIf { it >= 0 },
            thoughtsTokens = thoughtsTokens.takeIf { it >= 0 },
            cacheDetailsStatus = CacheDetailsStatus.NOT_PROVIDED,
        )
    }
}
