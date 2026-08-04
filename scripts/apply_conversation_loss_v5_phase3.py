#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt')
s = p.read_text()

old = '''            var lastEmitMs = 0L

            fun modelMessage() = ChatMessage(
'''
new = '''            var lastEmitMs = 0L
            var lastCheckpointMs = 0L

            fun modelMessage() = ChatMessage(
'''
if s.count(old) != 1:
    raise SystemExit('checkpoint state marker mismatch')
s = s.replace(old, new)

old = '''            fun flushThoughtSegment() {
                finishCurrentThoughtTiming()
                if (currentThoughtBuf.isNotEmpty()) {
                    appendMergedSegment(segments, MessageSegment(
                        type = "thought",
                        content = currentThoughtBuf.toString(),
                        signature = currentThoughtSignature,
                        durationMs = currentThoughtDurationMs.takeIf { it > 0L }
                    ))
                    currentThoughtBuf = StringBuilder()
                    currentThoughtSignature = null
                }
                currentThoughtDurationMs = 0L
            }

            suspend fun handleStreamEvent(event: StreamEvent) {
'''
new = '''            fun flushThoughtSegment() {
                finishCurrentThoughtTiming()
                if (currentThoughtBuf.isNotEmpty()) {
                    appendMergedSegment(segments, MessageSegment(
                        type = "thought",
                        content = currentThoughtBuf.toString(),
                        signature = currentThoughtSignature,
                        durationMs = currentThoughtDurationMs.takeIf { it > 0L }
                    ))
                    currentThoughtBuf = StringBuilder()
                    currentThoughtSignature = null
                }
                currentThoughtDurationMs = 0L
            }

            suspend fun checkpointStreamingMessage(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastCheckpointMs < 2_000L) return
                if (!isLatestPersist()) return
                if (conversations.getConversation(conversationId) == null) return
                val liveSegments = buildLiveSegments(
                    segments,
                    currentAnswerBuf,
                    currentThoughtBuf,
                    currentThoughtSignature,
                    liveThoughtDurationMs()
                )
                conversations.upsertMessage(MessageEntity(
                    id = modelMessageId,
                    conversationId = conversationId,
                    parentId = parentId,
                    text = totalText,
                    images = generatedImages.toList(),
                    thoughts = totalThoughts.ifBlank { null },
                    thoughtTitle = totalThoughtTitle,
                    tokenCount = totalTokenCount,
                    status = currentStatus,
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    thoughtTimeMs = totalThoughtTimeMs,
                    modelName = modelName,
                    toolCallJson = MessagePersistenceGuard.encodeSegmentsBounded(liveSegments),
                ))
                lastCheckpointMs = now
            }

            suspend fun handleStreamEvent(event: StreamEvent) {
'''
if s.count(old) != 1:
    raise SystemExit('checkpoint function marker mismatch')
s = s.replace(old, new)

old = '''                if (now - lastEmitMs >= 500 || isSignificant) {
                    onStreamUpdate(modelMessage())
                    lastEmitMs = now
                }
            }
'''
new = '''                if (now - lastEmitMs >= 500 || isSignificant) {
                    onStreamUpdate(modelMessage())
                    lastEmitMs = now
                }
                // Durable progress belongs to the originating conversation even while it is not
                // open. A process death or collector switch can therefore recover recent text
                // instead of the original empty SENDING placeholder.
                checkpointStreamingMessage(force = isSignificant)
            }
'''
if s.count(old) != 1:
    raise SystemExit('checkpoint invocation marker mismatch')
s = s.replace(old, new)

old = '''                            conversations.upsertMessage(MessageEntity(
                                id = modelMessageId, conversationId = conversationId, parentId = effectiveParentId,
                                text = MessagePersistenceGuard.clipText(totalText), images = generatedImages.toList(),
                                thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                                status = currentStatus, participant = Participant.MODEL, timestamp = startTime,
                                thoughtTimeMs = totalThoughtTimeMs, modelName = modelName, toolCallJson = segmentsJson
                            ))
'''
new = '''                            val terminalEntity = MessageEntity(
                                id = modelMessageId, conversationId = conversationId, parentId = effectiveParentId,
                                text = totalText, images = generatedImages.toList(),
                                thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                                status = currentStatus, participant = Participant.MODEL, timestamp = startTime,
                                thoughtTimeMs = totalThoughtTimeMs, modelName = modelName, toolCallJson = segmentsJson
                            )
                            var verified = false
                            var lastFailure: Throwable? = null
                            repeat(3) { attempt ->
                                try {
                                    conversations.upsertMessage(terminalEntity)
                                    val stored = conversations.getMessagesByIds(listOf(modelMessageId)).firstOrNull()
                                    val expected = MessagePersistenceGuard.sanitize(terminalEntity)
                                    verified = stored != null &&
                                        stored.conversationId == conversationId &&
                                        stored.status == expected.status &&
                                        stored.text == expected.text &&
                                        stored.thoughts == expected.thoughts &&
                                        stored.toolCallJson == expected.toolCallJson
                                    if (verified) return@repeat
                                    lastFailure = IllegalStateException("terminal message verification mismatch (attempt ${attempt + 1})")
                                } catch (failure: Throwable) {
                                    if (failure is CancellationException) throw failure
                                    lastFailure = failure
                                }
                            }
                            if (!verified) {
                                throw IllegalStateException(
                                    "Terminal message was not durably verified for $conversationId/$modelMessageId",
                                    lastFailure,
                                )
                            }
'''
if s.count(old) != 1:
    raise SystemExit('terminal persistence marker mismatch')
s = s.replace(old, new)

p.write_text(s)
