package com.newoether.agora.viewmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stable, user-readable error codes for failures produced by the tool execution layer.
 * Structured provider failures ({ok:false,error_code,detail}) map to a specific code and
 * carry the provider detail where it helps repair; sensitive/denied results never leak raw text.
 */
internal object ToolExecutionErrors {
    private val json = Json { ignoreUnknownKeys = true }

    fun unknownTool(name: String): String = message(
        code = "T001",
        title = "找不到工具",
        detail = "找不到名为“${safeName(name)}”的工具。请检查工具名称，或确认对应功能是否已启用。",
    )

    fun timeout(name: String): String = message(
        code = "T004",
        title = "工具执行超时",
        detail = "工具“${safeName(name)}”长时间没有完成。请检查网络或服务状态后重试。",
    )

    fun exception(name: String, error: Throwable): String =
        classified(name, error.localizedMessage.orEmpty())

    /** Converts provider-specific {ok:false,error:...} and plain error results to stable Chinese text. */
    fun normalizeResult(name: String, result: String): String {
        val lower = result.lowercase()
        val isError =
            Regex("\\\"ok\\\"\\s*:\\s*false", RegexOption.IGNORE_CASE).containsMatchIn(result) ||
                lower.startsWith("error:") || lower.startsWith("error executing tool") ||
                lower.startsWith("unknown tool")
        return if (isError) classified(name, result) else result
    }

    private fun classified(name: String, raw: String): String {
        val evidence = raw.lowercase()
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val code = parsed?.get("error_code")?.jsonPrimitive?.content
        val providerDetail = parsed?.get("detail")?.jsonPrimitive?.content
        val useful = providerDetail?.take(1000) ?: raw.take(1000)
        return when {
            evidence.containsAny("unknown tool", "unknown github tool", "unknown uma tool") ->
                unknownTool(name)
            code == "mutually_exclusive" || code == "no_change" ||
                evidence.containsAny("invalid tool arguments", "invalid argument", "must be positive", "invalid owner/name", "invalid repository", "invalid file", "invalid ref") ->
                message("T002", "工具参数不正确", "工具“${safeName(name)}”收到的参数格式或取值不正确：$useful")
            code == "missing_name" || code == "missing_content" || code == "missing_new_string" ||
                code == "missing_old_string" ||
                evidence.containsAny("required", "missing", "must not be blank", "cannot be blank") ->
                message("T003", "缺少工具参数", "工具“${safeName(name)}”缺少必填参数：$useful")
            evidence.containsAny("timed out", "timeout") -> timeout(name)
            code == "write_verification_failed" ->
                message("T010", "记忆写入未验证", "记忆文件写入后读回校验不一致：$useful")
            code == "memory_operation_failed" ->
                message("T011", "记忆操作失败", "记忆数据库没有完成操作：$useful")
            evidence.containsAny("denied", "confirmation unavailable", "not approved", "cancelled by user") ->
                message("T005", "工具操作已取消", "工具“${safeName(name)}”需要确认，但操作未获批准。没有执行对应的修改。")
            evidence.containsAny("not signed in", "api key", "not configured", "configuration") ->
                message("T006", "工具尚未配置", "工具“${safeName(name)}”缺少登录信息或必要配置。请先完成设置。")
            evidence.containsAny("http 401", "http 403", "forbidden", "permission", "access denied") ->
                message("T008", "工具权限不足", "当前账户没有执行工具“${safeName(name)}”所需的权限。请检查账户或令牌权限。")
            evidence.containsAny("network", "unable to resolve host", "unknown host", "connection refused", "failed to connect", "http 429", "http 500", "http 502", "http 503", "http 504") ->
                message("T007", "工具网络请求失败", "工具“${safeName(name)}”无法正常连接远程服务。请检查网络和服务状态后重试。")
            evidence.containsAny("parse", "malformed", "serialization", "unexpected json", "decode") ->
                message("T009", "工具结果无法读取", "工具“${safeName(name)}”返回了应用无法解析的数据：$useful")
            else -> message(
                "T099",
                "工具执行失败",
                "工具“${safeName(name)}”未能完成操作。原始诊断：$useful",
            )
        }
    }

    private fun message(code: String, title: String, detail: String): String =
        "[工具错误 $code] $title\n$detail"

    private fun safeName(name: String): String = name.ifBlank { "未命名工具" }.take(120)

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)
}
