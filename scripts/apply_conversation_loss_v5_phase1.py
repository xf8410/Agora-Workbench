#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt')
s = p.read_text()

replacements = [
("""    /** Load one older bounded window. The hard cap prevents a long scroll from rebuilding
     * the original unbounded Room/Compose heap pressure on Android OEM builds. */
    fun loadOlderMessages() {
        messageWindowSize.update { (it + MESSAGE_WINDOW_STEP).coerceAtMost(MAX_MESSAGE_WINDOW) }
    }
""", """    /** Load one older bounded window. Never discard the last coherent snapshot while loading. */
    fun loadOlderMessages() {
        messageWindowSize.update { (it + MESSAGE_WINDOW_STEP).coerceAtMost(MAX_MESSAGE_WINDOW) }
    }

    /** Last coherent mapped snapshot per conversation. Switching never destroys it pre-emptively. */
    private val coherentMessageSnapshots = java.util.concurrent.ConcurrentHashMap<String, List<ChatMessage>>()
"""),
("""                            .catch { cause ->
                                if (cause is CancellationException) throw cause
                                _historyLoadError.value = cause.message ?: "Unable to load conversation history"
                                _allMessages.value = emptyList(); _hasOlderMessages.value = false; _isSwitching.value = false
                            }
""", """                            .catch { cause ->
                                if (cause is CancellationException) throw cause
                                if (_currentConversationId.value == id) {
                                    _historyLoadError.value = cause.message ?: "Unable to load conversation history"
                                    // Preserve the last coherent snapshot. A failed refresh must never
                                    // turn an already visible conversation into a silent blank screen.
                                    coherentMessageSnapshots[id]?.let { _allMessages.value = it }
                                    _hasOlderMessages.value = false
                                    _isSwitching.value = false
                                }
                            }
"""),
("""                            val mapped = withContext(Dispatchers.Default) {
                                entities.map { entity ->
                                    val decodedSegments = entity.toolCallJson?.let { raw ->
                                        try { Json.decodeFromString<List<MessageSegment>>(raw) }
                                        catch (_: Exception) { null }
                                    }
                                    ChatMessage(
""", """                            val mapped = withContext(Dispatchers.Default) {
                                entities.mapNotNull { entity -> runCatching {
                                    val decodedSegments = entity.toolCallJson?.let { raw ->
                                        try { Json.decodeFromString<List<MessageSegment>>(raw) }
                                        catch (_: Exception) { null }
                                    }
                                    ChatMessage(
"""),
("""                                        attachmentMeta = entity.attachmentMeta?.let { raw ->
                                            try { Json.decodeFromString<AttachmentMeta>(raw) }
                                            catch (_: Exception) { null }
                                        }
                                    )
                                }
                            }
""", """                                        attachmentMeta = entity.attachmentMeta?.let { raw ->
                                            try { Json.decodeFromString<AttachmentMeta>(raw) }
                                            catch (_: Exception) { null }
                                        }
                                    )
                                }.onFailure { error ->
                                    DebugLog.e("ChatViewModel", "Skipping malformed history row ${entity.id} in $id", error)
                                }.getOrNull() }
                            }
"""),
("""                            _allMessages.value = mapped.map { msg ->
                                if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX) && msg.toolCall == null) {
                                    mappedById[msg.parentId]?.toolCall?.let { msg.copy(toolCall = it) } ?: msg
                                } else msg
                            }
                            if (!generationMirrorStarted) {
""", """                            val coherent = mapped.map { msg ->
                                if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX) && msg.toolCall == null) {
                                    mappedById[msg.parentId]?.toolCall?.let { msg.copy(toolCall = it) } ?: msg
                                } else msg
                            }
                            // collectLatest normally cancels stale collectors; this explicit identity
                            // gate is the correctness boundary for a late Room/mapping completion.
                            if (_currentConversationId.value != id) return@collect
                            if (entities.isNotEmpty() && coherent.isEmpty()) {
                                _historyLoadError.value = "Conversation rows exist but none could be decoded"
                                coherentMessageSnapshots[id]?.let { _allMessages.value = it }
                                _isSwitching.value = false
                                return@collect
                            }
                            coherentMessageSnapshots[id] = coherent
                            _allMessages.value = coherent
                            _isSwitching.value = false
                            if (!generationMirrorStarted) {
"""),
("""        _historyLoadError.value = null
        _allMessages.value = emptyList()
        _hasOlderMessages.value = false
        _currentConversationId.value = id
""", """        _historyLoadError.value = null
        // Do not clear the visible snapshot before Room has produced the target conversation's
        // first coherent result. The switching scrim covers the old snapshot during this interval.
        coherentMessageSnapshots[id]?.let { _allMessages.value = it }
        _hasOlderMessages.value = false
        _currentConversationId.value = id
""")
]

for old, new in replacements:
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'expected one match, got {count}: {old[:100]!r}')
    s = s.replace(old, new)

p.write_text(s)
