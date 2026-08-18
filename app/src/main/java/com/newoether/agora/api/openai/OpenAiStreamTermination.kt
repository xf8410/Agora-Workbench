package com.newoether.agora.api.openai

/**
 * OpenAI-compatible servers are allowed to report a completed choice before they close the SSE
 * response. Some proxies never send `[DONE]` and keep that HTTP response alive indefinitely.
 * Once a non-blank finish_reason is received, the choice itself is terminal and waiting for EOF
 * would leave the UI in its generating state forever.
 */
internal fun isTerminalOpenAiFinishReason(finishReason: String?): Boolean =
    !finishReason.isNullOrBlank()
