from pathlib import Path

path = Path("app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt")
text = path.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one Stage 3A source match, found {count}")
    text = text.replace(old, new)


replace_once(
    "import com.newoether.agora.model.ToolCallData\n",
    "import com.newoether.agora.model.ToolCallData\n"
    "import com.newoether.agora.model.ToolExecutionPresentation\n"
    "import com.newoether.agora.model.ToolExecutionStatus\n",
)
replace_once(
    '''        fun finishCurrentThoughtTiming() {
            val startedAt = currentThoughtStartMs ?: return
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > 0L) {
                cumulativeThoughtMs += elapsed
                currentThoughtDurationMs += elapsed
                totalThoughtTimeMs = cumulativeThoughtMs
            }
            currentThoughtStartMs = null
        }
''',
    '''        fun finishCurrentThoughtTiming() {
            val startedAt = currentThoughtStartMs ?: return
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > 0L) {
                cumulativeThoughtMs += elapsed
                currentThoughtDurationMs += elapsed
                totalThoughtTimeMs = cumulativeThoughtMs
            }
            currentThoughtStartMs = null
        }

        fun finishPendingTools(status: ToolExecutionStatus) {
            val finishedAt = System.currentTimeMillis()
            segments.indices.forEach { index ->
                val segment = segments[index]
                if (segment.type == "tool" && segment.toolResult == null &&
                    segment.toolStatus == ToolExecutionStatus.RUNNING
                ) {
                    segments[index] = segment.copy(
                        toolFinishedAtMs = finishedAt,
                        toolStatus = status,
                    )
                }
            }
        }
''',
)
replace_once(
    '''                        val ts = MessageSegment(type = "tool", toolName = event.name, toolArgs = event.arguments, toolResult = null, toolCallId = event.id, signature = event.signature)
                        appendMergedSegment(segments, ts)
''',
    '''                        val ts = MessageSegment(
                            type = "tool",
                            toolName = event.name,
                            toolArgs = event.arguments,
                            toolResult = null,
                            toolCallId = event.id,
                            signature = event.signature,
                            toolStartedAtMs = System.currentTimeMillis(),
                            toolStatus = ToolExecutionStatus.RUNNING,
                        )
                        appendMergedSegment(segments, ts)
''',
)
replace_once(
    '''                        if (idx >= 0) {
                            segments[idx] = segments[idx].copy(toolResult = clipped)
                            roundToolSegments.add(segments[idx])
                        }
''',
    '''                        if (idx >= 0) {
                            val finishedAt = System.currentTimeMillis()
                            val completed = segments[idx].copy(
                                toolResult = clipped,
                                toolFinishedAtMs = finishedAt,
                            )
                            segments[idx] = completed.copy(
                                toolStatus = ToolExecutionPresentation.status(
                                    completed,
                                    MessageStatus.SUCCESS,
                                ) ?: ToolExecutionStatus.SUCCESS,
                            )
                            roundToolSegments.add(segments[idx])
                        }
''',
)
replace_once(
    '''                        event.calls.forEach { call ->
                            appendMergedSegment(segments, MessageSegment(type = "tool", toolName = call.name, toolArgs = call.arguments, toolResult = null, toolCallId = call.id, signature = call.signature))
                        }
''',
    '''                        event.calls.forEach { call ->
                            appendMergedSegment(
                                segments,
                                MessageSegment(
                                    type = "tool",
                                    toolName = call.name,
                                    toolArgs = call.arguments,
                                    toolResult = null,
                                    toolCallId = call.id,
                                    signature = call.signature,
                                    toolStartedAtMs = System.currentTimeMillis(),
                                    toolStatus = ToolExecutionStatus.RUNNING,
                                ),
                            )
                        }
''',
)
replace_once(
    '''                            if (idx >= 0) {
                                segments[idx] = segments[idx].copy(toolResult = clipped)
                                roundToolSegments.add(segments[idx])
                            }
''',
    '''                            if (idx >= 0) {
                                val finishedAt = System.currentTimeMillis()
                                val completed = segments[idx].copy(
                                    toolResult = clipped,
                                    toolFinishedAtMs = finishedAt,
                                )
                                segments[idx] = completed.copy(
                                    toolStatus = ToolExecutionPresentation.status(
                                        completed,
                                        MessageStatus.SUCCESS,
                                    ) ?: ToolExecutionStatus.SUCCESS,
                                )
                                roundToolSegments.add(segments[idx])
                            }
''',
)
replace_once(
    '''        } catch (e: CancellationException) {
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
''',
    '''        } catch (e: CancellationException) {
            finishPendingTools(ToolExecutionStatus.STOPPED)
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            val isCancelled = generationJob?.isCancelled == true
            finishPendingTools(
                if (isCancelled) ToolExecutionStatus.STOPPED else ToolExecutionStatus.INTERRUPTED,
            )
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
''',
)

path.write_text(text)
