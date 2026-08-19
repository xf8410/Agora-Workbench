package com.newoether.agora.uma

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class UmaSessionJsonRole(
    val json_path: String,
    val source_path: String? = null,
    val role: String,
    val purpose: String,
    val direction: String? = null,
    val confidence: String,
    val evidence: List<String> = emptyList(),
    val top_level_keys: List<String> = emptyList(),
)

/** Produces an auditable explanation of what each generated JSON file is for. */
class UmaSessionJsonCatalog {
    fun decoded(
        sourcePath: String,
        jsonPath: String,
        value: JsonElement,
    ): UmaSessionJsonRole {
        val direction = when {
            sourcePath.startsWith("protocol/request/") -> "request"
            sourcePath.startsWith("protocol/response/") -> "response"
            else -> null
        }
        val keys = (value as? JsonObject)?.keys.orEmpty().sorted()
        val signals = buildList {
            direction?.let { add("path_direction=$it") }
            keys.take(20).forEach { add("top_level_key=$it") }
        }
        val purpose = when (direction) {
            "request" -> "发送给游戏服务器的请求业务数据；用于还原客户端提交了什么操作和参数。"
            "response" -> "游戏服务器返回的响应业务数据；用于还原状态变化、结果、奖励或后续选项。"
            else -> "从 MessagePack 原始载荷解码得到的业务数据；方向无法仅凭当前路径确定。"
        }
        return UmaSessionJsonRole(
            json_path = jsonPath,
            source_path = sourcePath,
            role = when (direction) {
                "request" -> "protocol_request_payload"
                "response" -> "protocol_response_payload"
                else -> "decoded_messagepack_payload"
            },
            purpose = purpose,
            direction = direction,
            confidence = if (direction == null) "medium" else "high",
            evidence = signals,
            top_level_keys = keys,
        )
    }

    fun builtInFiles(): List<UmaSessionJsonRole> = listOf(
        role("derived/manifest.json", "session_manifest", "Session 原始文件清单、字节数、哈希与派生统计；用于完整性核验。"),
        role("derived/exchanges.json", "protocol_exchange_index", "请求和响应交换索引；关联 URL、Header、原始 payload 与解码 JSON。"),
        role("derived/decoded_payloads.json", "decode_success_index", "成功解码的 payload 清单；记录原始路径、JSON 路径和消耗字节数。"),
        role("derived/decode_errors.json", "decode_error_log", "无法解码的 payload 及精确字节偏移；用于诊断格式或数据损坏。"),
    )

    fun renderText(sessionId: String, roles: List<UmaSessionJsonRole>): String = buildString {
        appendLine("Uma Session JSON 文件用途报告")
        appendLine("Session: $sessionId")
        appendLine("JSON 数量: ${roles.size}")
        appendLine()
        roles.sortedBy { it.json_path }.forEachIndexed { index, item ->
            appendLine("${index + 1}. ${item.json_path}")
            appendLine("   类型: ${item.role}")
            appendLine("   用途: ${item.purpose}")
            item.direction?.let { appendLine("   方向: $it") }
            item.source_path?.let { appendLine("   原始来源: $it") }
            appendLine("   置信度: ${item.confidence}")
            if (item.top_level_keys.isNotEmpty()) {
                appendLine("   顶层字段: ${item.top_level_keys.joinToString(", ")}")
            }
            if (item.evidence.isNotEmpty()) {
                appendLine("   判断依据: ${item.evidence.joinToString("; ")}")
            }
            appendLine()
        }
    }

    private fun role(path: String, role: String, purpose: String) = UmaSessionJsonRole(
        json_path = path,
        role = role,
        purpose = purpose,
        confidence = "high",
        evidence = listOf("Agora-generated fixed file"),
    )
}
