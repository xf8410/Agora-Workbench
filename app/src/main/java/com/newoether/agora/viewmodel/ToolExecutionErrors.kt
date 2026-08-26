package com.newoether.agora.viewmodel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Stable user-facing tool errors with the original actionable diagnostic retained. */
internal object ToolExecutionErrors {
    private val json = Json { ignoreUnknownKeys = true }

    fun unknownTool(name: String) = message("T001", "找不到工具", "找不到名为“${safe(name)}”的工具。请确认对应功能已启用。")
    fun timeout(name: String) = message("T004", "工具执行超时", "工具“${safe(name)}”超过时间限制。请检查网络、权限或目标服务。")
    fun exception(name: String, error: Throwable) = classified(name, error.localizedMessage.orEmpty())

    fun normalizeResult(name: String, result: String): String {
        val isJsonError = Regex("\\\"ok\\\"\\s*:\\s*false", RegexOption.IGNORE_CASE).containsMatchIn(result)
        val lower = result.lowercase()
        return if (isJsonError || lower.startsWith("error:") || lower.startsWith("unknown tool")) classified(name, result) else result
    }

    private fun classified(name: String, raw: String): String {
        val detail = raw.take(1200)
        val evidence = raw.lowercase()
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val code = parsed?.get("error_code")?.jsonPrimitive?.content
        val providerDetail = parsed?.get("detail")?.jsonPrimitive?.content
        val useful = providerDetail?.take(1000) ?: detail
        return when {
            code == "missing_name" || code == "missing_content" || code == "missing_old_string" -> message("T003", "缺少工具参数", "工具“${safe(name)}”参数不完整：$useful")
            code == "mutually_exclusive" || code == "no_change" -> message("T002", "工具参数不正确", "工具“${safe(name)}”：$useful")
            code == "write_verification_failed" -> message("T010", "记忆写入未验证", "写入后读回内容不一致：$useful")
            code == "memory_operation_failed" -> message("T011", "记忆操作失败", "记忆数据库没有完成操作：$useful")
            evidence.contains("unknown tool") -> unknownTool(name)
            evidence.containsAny("invalid json", "invalid argument", "invalid owner/name", "invalid repository", "invalid file", "invalid ref") -> message("T002", "工具参数不正确", "工具“${safe(name)}”的参数格式不正确：$useful")
            evidence.containsAny("required", "missing", "must not be blank", "cannot be blank") -> message("T003", "缺少工具参数", "工具“${safe(name)}”缺少必填参数：$useful")
            evidence.containsAny("timed out", "timeout") -> timeout(name)
            evidence.containsAny("denied", "not approved", "cancelled by user") -> message("T005", "工具操作已取消", "工具“${safe(name)}”未获确认，没有执行修改。")
            evidence.containsAny("not signed in", "api key", "not configured", "configuration") -> message("T006", "工具尚未配置", "工具“${safe(name)}”缺少登录信息或必要配置。")
            evidence.containsAny("http 401", "http 403", "forbidden", "permission", "access denied") -> message("T008", "工具权限不足", "当前账户没有执行工具“${safe(name)}”所需的权限。")
            evidence.containsAny("network", "unable to resolve host", "unknown host", "connection refused", "failed to connect", "http 429", "http 500", "http 502", "http 503", "http 504") -> message("T007", "工具网络请求失败", "工具“${safe(name)}”无法连接远程服务。")
            evidence.containsAny("parse", "malformed", "serialization", "unexpected json", "decode") -> message("T009", "工具结果无法读取", "工具“${safe(name)}”返回的数据无法解析：$useful")
            else -> message("T099", "工具执行失败", "工具“${safe(name)}”未完成操作。原始诊断：$useful")
        }
    }

    private fun message(code: String, title: String, detail: String) = "[工具错误 $code] $title\n$detail"
    private fun safe(name: String) = name.ifBlank { "未命名工具" }.take(120)
    private fun String.containsAny(vararg values: String) = values.any(::contains)
}
