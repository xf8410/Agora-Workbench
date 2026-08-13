package com.newoether.agora.util

object Constants {
    const val TOOL_MSG_PREFIX = "tool_"
    const val RESULT_MSG_PREFIX = "result_"
    const val TOOL_CALL_ID_PREFIX = "call_"

    const val MAX_EMBEDDING_TEXT_LENGTH = 8000
    const val MAX_CHUNK_TEXT_LENGTH = 500
    const val MAX_FILE_CONTENT_READ_LENGTH = 500_000
    const val WEB_FETCH_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    const val MAX_WEB_FETCH_HTML_LENGTH = 600_000
    const val MAX_TOOL_RESULT_LENGTH = 100_000
    const val MAX_PERSISTED_ROW_BYTES = 1_500_000
    const val MAX_PERSISTED_TEXT_CHARS = 500_000
    const val MODEL_FETCH_TIMEOUT_MS = 10_000L

    /**
     * Outer safety ceiling for one tool call. Large local SO endpoints can legitimately spend
     * several minutes enumerating IL2CPP metadata or reading process memory. Endpoint-specific
     * socket timeouts remain shorter where appropriate, and Stop cancels the generation coroutine.
     */
    const val TOOL_EXECUTION_TIMEOUT_MS = 30L * 60L * 1000L

    /** Ordinary small hlpatch endpoint timeout. */
    const val UMA_SO_SMALL_READ_TIMEOUT_MS = 60_000
    /** Large class/process-memory/private-file endpoint timeout. */
    const val UMA_SO_LARGE_READ_TIMEOUT_MS = 15 * 60_000

    const val SHELL_CONFIRM_TIMEOUT_MS = 300_000L
    const val SEARCH_METHOD_RAG = "rag"

    const val PROVIDER_LOCAL = "Local"
    const val PROVIDER_OPENAI = "OpenAI"
    const val PROVIDER_OLLAMA = "Ollama"
    const val PROVIDER_GOOGLE = "Google"
    const val PROVIDER_ANTHROPIC = "Anthropic"
    const val PROVIDER_DEEPSEEK = "DeepSeek"
    const val PROVIDER_QWEN = "Qwen"
    const val PROVIDER_GROQ = "Groq"
    const val PROVIDER_OPEN_ROUTER = "Open Router"
    const val PROVIDER_UNKNOWN = "Unknown"
    const val EXAMPLE_MODEL_ID = "gemini-1.5-flash"
}
