package com.newoether.agora.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Workspace agents cannot keep working after a response has returned. Remove sentences that claim
 * future CI observation or notification, and keep raw GitHub API payloads out of the visible lane.
 */
object WorkspaceOutputPolicy {
    private val unsupportedProgressClaims = listOf(
        Regex("(?i)[^。！？\\n]*(?:I(?:'ll| will)|we(?:'ll| will))\\s+(?:keep\\s+)?(?:watch\\s+|monitor\\s+|poll\\s+)[^。！？\\n]*[。！？]?(?:\\n|$)"),
        Regex("[^。！？\\n]*(?:我会|将会|会继续|正在|持续)(?:监控|观察|轮询|盯着|等待)(?:CI|构建|Actions|工作流|运行)[^。！？\\n]*[。！？]?(?:\\n|$)"),
        Regex("[^。！？\\n]*(?:完成后|有结果后)(?:我会)?(?:通知|告诉|汇报)[^。！？\\n]*[。！？]?(?:\\n|$)"),
    )

    private val githubTreeKeys = setOf("path", "type", "mode", "sha", "size")

    fun sanitize(text: String): String {
        val trimmed = text.trim()
        if (isRawGithubTreePayload(trimmed)) {
            return "GitHub 文件树已更新；原始文件列表已保留在工具结果中。"
        }
        var result = text
        unsupportedProgressClaims.forEach { result = result.replace(it, "") }
        return result.lines()
            .map { it.trimEnd() }
            .fold(mutableListOf<String>()) { out, line ->
                if (line.isNotBlank() || out.lastOrNull()?.isNotBlank() == true) out.add(line)
                out
            }
            .joinToString("\n")
            .trim()
    }

    private fun isRawGithubTreePayload(text: String): Boolean {
        if (text.length < 200) return false
        if (isTreeJson(text)) return true

        // Tool output can be truncated or prefixed by prose. A repeated Git tree entry
        // signature is sufficient to suppress the payload even when the JSON is incomplete.
        val signature = Regex("\\\"path\\\"\\s*:\\s*\\\"[^\\\"]+\\\"\\s*,\\s*\\\"type\\\"\\s*:\\s*\\\"blob\\\".*?\\\"sha\\\"\\s*:", RegexOption.DOT_MATCHES_ALL)
        return signature.findAll(text).count() >= 2
    }

    private fun isTreeJson(text: String): Boolean = runCatching {
        val element = Json.parseToJsonElement(text)
        val objects = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        if (objects.isEmpty()) return@runCatching false
        val treeLike = objects.count { obj ->
            obj.keys.intersect(githubTreeKeys).size >= 4 &&
                obj["type"]?.toString()?.trim('"') == "blob"
        }
        treeLike >= maxOf(1, objects.size / 2)
    }.getOrDefault(false)
}
