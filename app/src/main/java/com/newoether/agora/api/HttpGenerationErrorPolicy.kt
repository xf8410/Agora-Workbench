package com.newoether.agora.api

/**
 * Classifies provider failures from both the HTTP status and the provider response.
 * A gateway status such as 502 is never treated as a context overflow without response evidence.
 */
internal object HttpGenerationErrorPolicy {
    private val contextPatterns = listOf(
        Regex("maximum\\s+context\\s+length", RegexOption.IGNORE_CASE),
        Regex("context\\s+(length|window)\\s+(is\\s+)?(exceeded|overflow|too\\s+(large|long))", RegexOption.IGNORE_CASE),
        Regex("exceed(s|ed)?[^\\n]{0,80}context[^\\n]{0,40}(length|window|limit)", RegexOption.IGNORE_CASE),
        Regex("too\\s+many\\s+(input\\s+|prompt\\s+)?tokens", RegexOption.IGNORE_CASE),
        Regex("(input|prompt)[^\\n]{0,40}(too\\s+long|token\\s+limit)", RegexOption.IGNORE_CASE),
        Regex("token[^\\n]{0,40}(budget|limit)[^\\n]{0,40}(exceeded|overflow)", RegexOption.IGNORE_CASE),
        Regex("请求.{0,20}(超过|超出).{0,20}(上下文|令牌|token)", RegexOption.IGNORE_CASE),
        Regex("(上下文|输入).{0,20}(过长|超限|超过)", RegexOption.IGNORE_CASE),
    )

    fun isContextOverflow(responseBody: String): Boolean =
        responseBody.isNotBlank() && contextPatterns.any { it.containsMatchIn(responseBody) }

    fun shouldRetry(statusCode: Int, responseBody: String): Boolean =
        !isContextOverflow(responseBody) && statusCode in setOf(429, 502, 503, 504)

    fun contextErrorOrNull(statusCode: Int, responseBody: String): GenerationError.ContextWindow? =
        if (isContextOverflow(responseBody)) {
            GenerationError.ContextWindow(statusCode = statusCode)
        } else null
}
