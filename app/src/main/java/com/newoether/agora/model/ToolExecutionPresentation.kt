package com.newoether.agora.model

/**
 * Pure compatibility policy for rendering persisted tool execution metadata.
 *
 * New messages carry explicit timestamps and status. Legacy messages may only have a result (and,
 * in some versions, durationMs), so callers must not turn missing timing data into a fake "0 s".
 */
object ToolExecutionPresentation {
    fun durationMs(segment: MessageSegment): Long? {
        if (segment.type != "tool") return null
        val startedAt = segment.toolStartedAtMs
        val finishedAt = segment.toolFinishedAtMs
        if (startedAt != null && finishedAt != null && finishedAt >= startedAt) {
            return (finishedAt - startedAt).takeIf { it > 0L }
        }
        return segment.durationMs?.takeIf { it > 0L }
    }

    fun status(segment: MessageSegment, messageStatus: MessageStatus): ToolExecutionStatus? {
        if (segment.type != "tool") return null
        segment.toolStatus?.let { return it }

        val result = segment.toolResult
        if (result == null) {
            return when (messageStatus) {
                MessageStatus.SENDING,
                MessageStatus.THINKING,
                MessageStatus.TOOL_CALLING,
                MessageStatus.TRANSCRIBING -> ToolExecutionStatus.RUNNING
                MessageStatus.STOPPED -> ToolExecutionStatus.STOPPED
                MessageStatus.ERROR -> ToolExecutionStatus.INTERRUPTED
                MessageStatus.SUCCESS -> ToolExecutionStatus.INTERRUPTED
            }
        }

        return when {
            isTimeoutResult(result) -> ToolExecutionStatus.TIMED_OUT
            isFailureResult(result) -> ToolExecutionStatus.FAILED
            else -> ToolExecutionStatus.SUCCESS
        }
    }

    fun isTerminal(status: ToolExecutionStatus?): Boolean = when (status) {
        ToolExecutionStatus.SUCCESS,
        ToolExecutionStatus.FAILED,
        ToolExecutionStatus.TIMED_OUT,
        ToolExecutionStatus.STOPPED,
        ToolExecutionStatus.INTERRUPTED -> true
        ToolExecutionStatus.RUNNING, null -> false
    }

    private fun isTimeoutResult(result: String): Boolean {
        val normalized = result.trimStart().lowercase()
        return normalized.contains("[工具错误 t004]") ||
            normalized.startsWith("timeout") ||
            normalized.startsWith("timed out") ||
            normalized.contains("tool execution timed out")
    }

    private fun isFailureResult(result: String): Boolean {
        val normalized = result.trimStart().lowercase()
        return normalized.startsWith("[工具错误 ") ||
            normalized.startsWith("error:") ||
            normalized.startsWith("error executing tool") ||
            Regex("\\\"ok\\\"\\s*:\\s*false", RegexOption.IGNORE_CASE).containsMatchIn(result)
    }
}
