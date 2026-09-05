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

    /** juece-ramen decision datasource (收集数据工作台): loopback-only JSON API on the device. */
    const val RAMEN_DEFAULT_BASE_URL = "http://127.0.0.1:18767"
    /** juece-ramen endpoints are small JSON reads; keep timeouts tight but safe. */
    const val RAMEN_CONNECT_TIMEOUT_MS = 5_000
    const val RAMEN_READ_TIMEOUT_MS = 15_000
    /** Contract paths — fixed by the juece-ramen API, must not be renamed. */
    const val RAMEN_PATH_HEALTH = "/health"
    const val RAMEN_PATH_STATUS = "/status"
    const val RAMEN_PATH_DATA = "/data"
    const val RAMEN_PATH_SUMMARY = "/summary"
    const val RAMEN_DATA_LIMIT_DEFAULT = 50
    const val RAMEN_DATA_LIMIT_MAX = 500
    /** Hard ceiling for one juece-ramen response body (chars). */
    const val RAMEN_MAX_RESPONSE_CHARS = 4 * 1024 * 1024
    /** Configured base-URL sanity bound. */
    const val RAMEN_BASE_URL_MAX_LENGTH = 200
    /** Fixed upload target for ramen decision data (GitHub fallback channel of juece-ramen). */
    const val RAMEN_UPLOAD_REPO = "xf8410/uma-lamianbei-yuchengshuju"
    /** The peer's fallback channel commits to main via the Contents API; PC tools consume main. */
    const val RAMEN_UPLOAD_BRANCH = "main"
    /** Commit message prefix for ramen data uploads. */
    const val RAMEN_UPLOAD_COMMIT_MESSAGE_PREFIX = "Agora 收集数据工作台上传"
    /** Records fetched per /data page while collecting a full upload. */
    const val RAMEN_UPLOAD_PAGE_LIMIT = 200
    /** Single-upload safety cap so one accidental full dump cannot exhaust memory. */
    const val RAMEN_UPLOAD_MAX_RECORDS = 100_000
    /** JSONL byte ceiling kept under GitHub's 100 MiB blob limit. */
    const val RAMEN_UPLOAD_MAX_JSONL_BYTES = 90L * 1024L * 1024L
    /** Sequence-number field of a decision-log record, used to advance the /data after cursor. */
    const val RAMEN_RECORD_SEQ_FIELD = "seq"

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
