package com.newoether.agora.api

/** Typed error hierarchy for LLM generation failures. User-facing text is concise Chinese. */
sealed class GenerationError {
    data class Network(val statusCode: Int, val message: String) : GenerationError()
    data class Api(val code: String?, val type: String?, val message: String) : GenerationError()
    data class ContextWindow(val statusCode: Int) : GenerationError()
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
                0 -> "无法连接到模型服务。请检查网络、代理和服务地址后重试。"
                400 -> "请求格式不正确，模型服务无法处理。请检查模型设置或换一个模型后重试。"
                401 -> "身份验证失败。请检查这个模型提供商的 API 密钥。"
                403 -> "模型服务拒绝了请求。请检查 API 密钥权限、账户状态或模型访问权限。"
                404 -> "找不到模型接口或模型。请检查服务地址、/v1 路径和模型名称。"
                408 -> "模型服务等待请求超时。请检查网络后重试。"
                413 -> "发送的内容太大。请减少附件或消息内容后重试。"
                422 -> "模型服务无法接受当前参数。请检查模型名称和生成设置。"
                429 -> "请求过于频繁或额度已用完。请稍后重试，并检查账户额度。"
                500 -> "模型服务内部出错。你的消息已保留，请稍后重试。"
                502 -> "上游模型服务暂时没有正常响应（502）。这不一定是消息过长，请稍后重试。"
                503 -> "模型服务当前不可用（503）。请稍后重试。"
                504 -> "上游模型服务响应超时（504）。请稍后重试。"
                in 500..599 -> "模型服务暂时出错（$statusCode）。你的消息已保留，请稍后重试。"
                else -> "请求模型服务失败（$statusCode）。请检查网络和模型设置后重试。"
            }
        is Api -> HttpGenerationErrorPolicy.contextErrorOrNull(code?.toIntOrNull() ?: 0, message)?.userMessage()
            ?: apiMessage(code, type, message)
        is ContextWindow -> "当前对话内容超过了模型的消息上限，模型无法继续读取。请新建对话，或删除、缩短较早的消息后重试。"
        is SseParse -> "模型返回的数据格式不完整，应用无法读取这次回复。请重试；如果反复出现，请检查服务兼容性。"
        is ToolExecution -> "工具“$toolName”执行失败。请检查工具设置后重试。"
        is Transcription -> "图片转文字失败。请检查图片格式、转写模型和网络后重试。"
        is Embedding -> "文本索引生成失败。请检查嵌入模型、API 密钥和服务地址。"
        is LocalModel -> localizeFallback(message, "本地模型运行失败。请检查模型文件和设备内存。")
        is Configuration -> localizeFallback(message, "模型配置不完整。请检查 API 密钥、服务地址和模型名称。")
        is Unknown -> unknownMessage(cause)
        Cancelled -> "已停止生成。"
        Timeout -> "连接或发送请求超时。你的消息和已经生成的内容已保留，请检查网络后重试。"
    }

    private fun apiMessage(code: String?, type: String?, raw: String): String {
        val evidence = listOfNotNull(code, type, raw).joinToString(" ").lowercase()
        return when {
            evidence.containsAny("invalid_api_key", "incorrect api key", "unauthorized", "authentication") ->
                "身份验证失败。请检查这个模型提供商的 API 密钥。"
            evidence.containsAny("permission", "forbidden", "access denied") ->
                "没有权限使用这个模型。请检查 API 密钥权限或更换模型。"
            evidence.containsAny("rate_limit", "rate limit", "too many requests", "quota", "insufficient_quota") ->
                "请求过于频繁或额度已用完。请稍后重试，并检查账户额度。"
            evidence.containsAny("model_not_found", "model not found", "does not exist") ->
                "找不到所选模型。请刷新模型列表或检查模型名称。"
            evidence.containsAny("content_filter", "content policy", "safety") ->
                "模型服务因内容安全规则拒绝了这次请求。请调整消息内容后重试。"
            evidence.containsAny("billing", "payment", "credit balance") ->
                "模型账户余额或计费状态有问题。请检查提供商账户。"
            else -> "模型服务拒绝了这次请求。请检查模型、API 密钥和生成设置后重试。"
        }
    }

    private fun unknownMessage(error: Throwable): String {
        val text = error.localizedMessage.orEmpty().lowercase()
        return when {
            text.containsAny("unable to resolve host", "unknown host") -> "无法找到模型服务器。请检查网络、代理和服务地址。"
            text.containsAny("connection refused", "failed to connect") -> "模型服务器拒绝连接。请确认服务已启动且地址正确。"
            text.containsAny("certificate", "ssl", "handshake") -> "安全连接失败。请检查系统时间、证书或 HTTPS 服务地址。"
            text.containsAny("outofmemory", "out of memory") -> "设备内存不足，无法完成这次回复。请减少附件或对话长度后重试。"
            else -> "生成回复时发生未知错误。你的消息已保留，请重试。"
        }
    }

    private fun localizeFallback(raw: String, fallback: String): String =
        if (raw.any { it.code in 0x4E00..0x9FFF }) raw else fallback

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
}
