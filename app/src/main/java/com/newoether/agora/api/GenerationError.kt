package com.newoether.agora.api

/** Typed error hierarchy for LLM generation failures. */
sealed class GenerationError {
    data class Network(val statusCode: Int, val message: String) : GenerationError()
    data class Api(val code: String?, val type: String?, val message: String) : GenerationError()
    data class ContextWindow(val statusCode: Int, val providerMessage: String) : GenerationError()
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
        is Network -> when (statusCode) {
            401 -> "身份验证失败。请检查这个模型提供商的 API 密钥。"
            403 -> "没有权限使用这个模型。请检查 API 密钥权限或更换模型。"
            429 -> "请求过于频繁或额度已用完。请稍后重试，并检查账户额度。"
            404 -> "请求的接口地址不存在。请检查服务地址配置。"
            in 500..599 -> "服务器错误（$statusCode）。服务可能暂时不可用。"
            else -> "网络错误（$statusCode）：$message"
        }
        is Api -> apiMessage(code, type, message)
        is ContextWindow -> "模型服务报告本次请求超出上下文长度。请减少附件或新建对话后重试。"
        is SseParse -> "解析服务端响应失败。"
        is ToolExecution -> "工具"$toolName"执行失败。请检查参数或重试。"
        is Transcription -> "图片转录失败：$message"
        is Embedding -> "向量化失败：$message"
        is LocalModel -> message
        is Configuration -> message
        is Unknown -> unknownMessage(cause)
        Cancelled -> "已取消生成。"
        Timeout -> "网络连接或请求超时。普通和流式请求没有本地读取时间限制。本地消息和已完成的工具进度已保留，可从最近的检查点重试或继续。"
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
