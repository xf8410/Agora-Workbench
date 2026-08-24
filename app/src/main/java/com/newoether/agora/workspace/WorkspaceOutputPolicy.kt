package com.newoether.agora.workspace

/**
 * Workspace agents cannot keep working after a response has returned. Remove sentences that claim
 * future CI observation or notification. Current statuses, conclusions, run IDs and URLs remain.
 */
object WorkspaceOutputPolicy {
    private val unsupportedProgressClaims = listOf(
        Regex("(?i)[^。！？\\n]*(?:I(?:'ll| will)|we(?:'ll| will))\\s+(?:keep\\s+)?(?:watch|monitor|poll)[^。！？\\n]*[。！？]?(?:\\n|$)"),
        Regex("[^。！？\\n]*(?:我会|将会|会继续|正在|持续)(?:监控|观察|轮询|盯着|等待)(?:CI|构建|Actions|工作流|运行)[^。！？\\n]*[。！？]?(?:\\n|$)"),
        Regex("[^。！？\\n]*(?:完成后|有结果后)(?:我会)?(?:通知|告诉|汇报)[^。！？\\n]*[。！？]?(?:\\n|$)"),
    )

    fun sanitize(text: String): String {
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
}
