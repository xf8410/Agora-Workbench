package com.newoether.agora.api

/**
 * HTTP status alone cannot identify a context overflow. In particular, gateways commonly return
 * 502 for unrelated upstream failures. Only response evidence may classify a request as too large.
 */
internal object HttpGenerationErrorPolicy {
    private val contextPatterns = listOf(
        Regex("maximum\\s+context\\s+length", RegexOption.IGNORE_CASE),
        Regex("context\\s+(length|window)\\s+(is\\s+)?(exceeded|overflow|too\\s+(large|long))", RegexOption.IGNORE_CASE),
        Regex("exceed(s|ed)?[^\\n]{0,80}context[^\\n]{0,40}(length|window|limit)", RegexOption.IGNORE_CASE),
        Regex("too\\s+many\\s+(input\\s+|prompt\\s+)?tokens", RegexOption.IGNORE_CASE),
        Regex("(input|prompt)[^\\n]{0,40}(too\\s+long|token\\s+limit)", RegexOption.IGNORE_CASE),
        Regex("token[^\\n]{0,40}(budget|limit)[^\\n]{0,40}(exceeded|overflow)", RegexOption.IGNORE_CASE),
    )

    fun isContextOverflow(responseBody: String): Boolean {
        if (responseBody.isBlank()) return false
        return contextPatterns.any { it.containsMatchIn(responseBody) }
    }

    fun shouldRetry(statusCode: Int, responseBody: String): Boolean =
        !isContextOverflow(responseBody) && statusCode in setOf(429, 502, 503, 504)

    fun contextErrorOrNull(statusCode: Int, responseBody: String): GenerationError.ContextWindow? =
        if (isContextOverflow(responseBody)) {
            GenerationError.ContextWindow(
                statusCode = statusCode,
                providerMessage = responseBody.take(MAX_PROVIDER_MESSAGE_LENGTH),
            )
        } else null

    private const val MAX_PROVIDER_MESSAGE_LENGTH = 4_000
}
