package com.newoether.agora.api.openai

/**
 * OpenAI-compatible servers may report a completed choice but omit `[DONE]` and keep the HTTP
 * response open. The terminal JSON line must still be delivered to the provider (it can contain
 * usage or final tool-call metadata); the transport closes on the following read.
 */
internal fun isTerminalOpenAiSseLine(line: String): Boolean {
    if (!line.startsWith("data:")) return false
    val compact = line.filterNot(Char::isWhitespace)
    val key = "\"finish_reason\":"
    val valueStart = compact.indexOf(key)
    if (valueStart < 0) return false
    val value = compact.substring(valueStart + key.length)
    return !value.startsWith("null")
}
