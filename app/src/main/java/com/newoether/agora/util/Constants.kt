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

    // ── File Courier（文件投递）───────────────────────────────
    // 机主指定的默认中转仓：手机文件经 zip 分卷后上传到该私有仓供云端取回。
    const val COURIER_DEFAULT_REPO = "xf8410/bestsoccer-gala"
    // 机主私有仓的默认分支（manifest 与卷都提交到这个分支）。
    const val COURIER_DEFAULT_BRANCH = "main"
    // 单卷默认大小。来源：机主工单示例 max_volume_mb=90。
    const val COURIER_DEFAULT_VOLUME_MB = 90
    // 单任务总字节数默认上限。来源：机主工单示例 max_total_mb=400。
    const val COURIER_DEFAULT_TOTAL_MB = 400
    // 单任务文件数默认上限。来源：机主工单示例 max_files=500。
    const val COURIER_DEFAULT_MAX_FILES = 500
    // upload_phone_file 的默认分卷阈值。来源：机主测试对象为 30-50MB 级 APK zip，
    // 取 32MB 每片保证 50MB 文件切 2 片且每片远低于 Blob API 上限。
    const val COURIER_FILE_SPLIT_DEFAULT_MB = 32
    // GitHub Git Data "Create a blob" API 官方上限 100MB，留 5MB 安全水位。
    const val COURIER_MAX_SINGLE_BLOB_MB = 95
    // list_phone_dir 返回条目上限：工具结果会进入模型上下文（MAX_TOOL_RESULT_LENGTH=100k 字符）。
    const val COURIER_LIST_MAX_ENTRIES = 1000
    // 单卷上传失败重试次数。来源：机主工单"重试 2 次后放弃并记 errors"。
    const val COURIER_UPLOAD_RETRIES = 2
    // su 可用性探测的超时（机主手机无 root，探测只是兜底策略，快速失败）。
    const val COURIER_SU_PROBE_TIMEOUT_MS = 3_000
    // su cat 读文件前的确认输出行数上限（探测 su 时防止超大输出）。
    const val COURIER_SU_LIST_TIMEOUT_MS = 15_000
}
