package com.newoether.agora.api

/** Typed error hierarchy for LLM generation failures. */
sealed class GenerationError {
    data class Network(val statusCode: Int, val message: String) : GenerationError()
    data class Api(val code: String?, val type: String?, val message: String) : GenerationError()

    /**
     * The provider explicitly reported that the submitted prompt exceeded its context/token limit.
     * This must never be inferred from HTTP 502 alone.
     */
    data class ContextWindow(
        val statusCode: Int,
        val providerMessage: String,
    ) : GenerationError()

    data class SseParse(val rawLine: String, val cause: String) : GenerationError()
    data class ToolExecution(val toolName: String, val arguments: String, val message: String) : GenerationError()
    data class Transcription(val imagePath: String, val message: String) : GenerationError()
    data class Embedding(val modelId: String, val message: String) : GenerationError()
    data class LocalModel(val message: String) : GenerationError()
    data class Configuration(val message: String) : GenerationError()
    data class Unknown(val cause: Throwable) : GenerationError()
    object Cancelled : GenerationError()
    object Timeout : GenerationError()

    fun userMessage(): String = when (this) {
        is Network -> HttpGenerationErrorPolicy.contextErrorOrNull(statusCode, message)?.userMessage()
            ?: when (statusCode) {
                401 -> "Authentication failed. Please check your API key."
                429 -> "Rate limit exceeded. Please wait and try again."
                502 -> "Gateway error (502). The upstream service failed; this does not by itself mean the context is too long."
                in 500..599 -> "Server error ($statusCode). The service may be temporarily unavailable."
                else -> "Network error ($statusCode): $message"
            }
        is Api -> HttpGenerationErrorPolicy.contextErrorOrNull(code?.toIntOrNull() ?: 0, message)?.userMessage()
            ?: buildString {
                if (code != null) append(code)
                if (type != null) append(" [$type]")
                if (isNotEmpty()) append(": ")
                append(message)
            }
        is ContextWindow -> "The provider reported that this request exceeds its context or input-token limit. Reduce the configured context window or start a new conversation. Provider response: $providerMessage"
        is SseParse -> "Failed to parse server response."
        is ToolExecution -> "Tool '$toolName' failed: $message"
        is Transcription -> "Image transcription failed: $message"
        is Embedding -> "Embedding failed: $message"
        is LocalModel -> message
        is Configuration -> message
        is Unknown -> cause.localizedMessage ?: "An unexpected error occurred."
        Cancelled -> "Generation cancelled."
        Timeout -> "Request timed out."
    }
}
