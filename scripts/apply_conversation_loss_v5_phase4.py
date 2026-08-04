#!/usr/bin/env python3
from pathlib import Path

# DAO: add one-shot newest page + strict keyset older page. Keep existing Flow API for compatibility.
p = Path('app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt')
s = p.read_text()
marker = '''    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun getMessageCountForConversation(conversationId: String): Flow<Int>
'''
insert = '''    @Query("""
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getNewestMessagesPage(conversationId: String, limit: Int): List<MessageEntity>

    @Query("""
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId))
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getOlderMessagesPage(
        conversationId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int,
    ): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    fun getMessageCountForConversation(conversationId: String): Flow<Int>
'''
if s.count(marker) != 1: raise SystemExit('DAO marker mismatch')
s = s.replace(marker, insert)
p.write_text(s)

# Repository wrappers.
p = Path('app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt')
s = p.read_text()
marker = '''    fun getMessageCountForConversation(conversationId: String): Flow<Int> =
        chatDao.getMessageCountForConversation(conversationId)
'''
insert = '''    suspend fun getNewestMessagesPage(conversationId: String, limit: Int = 24): List<MessageEntity> =
        chatDao.getNewestMessagesPage(conversationId, limit.coerceIn(1, 100))
            .sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })

    suspend fun getOlderMessagesPage(
        conversationId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int = 24,
    ): List<MessageEntity> = chatDao.getOlderMessagesPage(
        conversationId, beforeTimestamp, beforeId, limit.coerceIn(1, 100)
    ).sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })

    fun getMessageCountForConversation(conversationId: String): Flow<Int> =
        chatDao.getMessageCountForConversation(conversationId)
'''
if s.count(marker) != 1: raise SystemExit('repo marker mismatch')
s = s.replace(marker, insert)
p.write_text(s)

# VM: remove the permanent 500 cap and replace expanding LIMIT with accumulated keyset pages.
p = Path('app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt')
s = p.read_text()
s = s.replace('''        private const val MESSAGE_WINDOW_STEP = 24
        private const val MAX_MESSAGE_WINDOW = 500
''', '''        private const val MESSAGE_PAGE_SIZE = 24
''')

old = '''    private val messageWindowSize = MutableStateFlow(INITIAL_MESSAGE_WINDOW)
    private val _hasOlderMessages = MutableStateFlow(false)
'''
new = '''    private val _hasOlderMessages = MutableStateFlow(false)
    private val historyPageRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
'''
if s.count(old) != 1: raise SystemExit('window state marker mismatch')
s = s.replace(old, new)

old = '''    /** Load one older bounded window. Never discard the last coherent snapshot while loading. */
    fun loadOlderMessages() {
        messageWindowSize.update { (it + MESSAGE_WINDOW_STEP).coerceAtMost(MAX_MESSAGE_WINDOW) }
    }
'''
new = '''    /** Request one strict keyset page; loaded pages accumulate without a permanent total cap. */
    fun loadOlderMessages() {
        if (_hasOlderMessages.value) historyPageRequests.tryEmit(Unit)
    }
'''
if s.count(old) != 1: raise SystemExit('load older marker mismatch')
s = s.replace(old, new)

old = '''        messageWindowSize.value = INITIAL_MESSAGE_WINDOW
        _historyLoadError.value = null
'''
new = '''        _historyLoadError.value = null
'''
if s.count(old) != 1: raise SystemExit('select reset marker mismatch')
s = s.replace(old, new)

start = s.find('''                        var generationMirrorStarted = false
                        combine(
                            messageWindowSize.flatMapLatest { limit ->
''')
end_marker = '''                        }
                    }
                } else {
'''
end = s.find(end_marker, start)
if start < 0 or end < 0: raise SystemExit('history collector block markers mismatch')
old_block = s[start:end]
new_block = '''                        var generationMirrorStarted = false
                        var loadedEntities = emptyList<MessageEntity>()

                        suspend fun mapAndPublish(entities: List<MessageEntity>) {
                            val mapped = withContext(Dispatchers.Default) {
                                entities.mapNotNull { entity -> runCatching {
                                    val decodedSegments = entity.toolCallJson?.let { raw ->
                                        try { Json.decodeFromString<List<MessageSegment>>(raw) }
                                        catch (_: Exception) { null }
                                    }
                                    ChatMessage(
                                        id = entity.id,
                                        parentId = entity.parentId,
                                        text = SearchResultFormatter.format(entity.text, appContext),
                                        images = entity.images,
                                        thoughts = entity.thoughts,
                                        thoughtTitle = entity.thoughtTitle,
                                        tokenCount = entity.tokenCount,
                                        status = entity.status,
                                        participant = entity.participant,
                                        timestamp = entity.timestamp,
                                        thoughtTimeMs = entity.thoughtTimeMs,
                                        modelName = entity.modelName,
                                        segments = decodedSegments ?: entity.thoughts
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { listOf(MessageSegment(type = "thought", content = it)) },
                                        toolCall = decodedSegments?.lastOrNull { it.type == "tool" }?.let { seg ->
                                            ToolCallData(seg.toolName.orEmpty(), seg.toolArgs ?: "{}",
                                                SearchResultFormatter.format(seg.toolResult.orEmpty(), appContext))
                                        },
                                        attachmentMeta = entity.attachmentMeta?.let { raw ->
                                            try { Json.decodeFromString<AttachmentMeta>(raw) }
                                            catch (_: Exception) { null }
                                        }
                                    )
                                }.onFailure { error ->
                                    DebugLog.e("ChatViewModel", "Skipping malformed history row ${entity.id} in $id", error)
                                }.getOrNull() }
                            }
                            val mappedById = mapped.associateBy { it.id }
                            val coherent = mapped.map { msg ->
                                if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX) && msg.toolCall == null) {
                                    mappedById[msg.parentId]?.toolCall?.let { msg.copy(toolCall = it) } ?: msg
                                } else msg
                            }
                            if (_currentConversationId.value != id) return
                            if (entities.isNotEmpty() && coherent.isEmpty()) {
                                _historyLoadError.value = "Conversation rows exist but none could be decoded"
                                coherentMessageSnapshots[id]?.let { _allMessages.value = it }
                                _isSwitching.value = false
                                return
                            }
                            coherentMessageSnapshots[id] = coherent
                            _allMessages.value = coherent
                            _historyLoadError.value = null
                            _isSwitching.value = false
                            if (!generationMirrorStarted) {
                                generationMirrorStarted = true
                                generationMirror.publishCurrent(id, state)
                                switchScope.launch { generationMirror.collect(id, state) }
                            }
                        }

                        suspend fun loadInitialPage() {
                            loadedEntities = convRepo.getNewestMessagesPage(id, INITIAL_MESSAGE_WINDOW)
                            _hasOlderMessages.value = loadedEntities.size == INITIAL_MESSAGE_WINDOW
                            mapAndPublish(loadedEntities)
                        }

                        try {
                            loadInitialPage()
                            historyPageRequests.collect {
                                if (_currentConversationId.value != id || !_hasOlderMessages.value) return@collect
                                val oldest = loadedEntities.firstOrNull() ?: run {
                                    _hasOlderMessages.value = false
                                    return@collect
                                }
                                val older = convRepo.getOlderMessagesPage(
                                    id, oldest.timestamp, oldest.id, MESSAGE_PAGE_SIZE
                                )
                                if (older.isEmpty()) {
                                    _hasOlderMessages.value = false
                                } else {
                                    val knownIds = loadedEntities.asSequence().map { it.id }.toHashSet()
                                    loadedEntities = (older.filterNot { it.id in knownIds } + loadedEntities)
                                        .sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
                                    _hasOlderMessages.value = older.size == MESSAGE_PAGE_SIZE
                                    mapAndPublish(loadedEntities)
                                }
                            }
                        } catch (cause: CancellationException) {
                            throw cause
                        } catch (cause: Exception) {
                            if (_currentConversationId.value == id) {
                                DebugLog.e("ChatViewModel", "History keyset load failed for $id", cause)
                                _historyLoadError.value = cause.message ?: "Unable to load conversation history"
                                coherentMessageSnapshots[id]?.let { _allMessages.value = it }
                                _isSwitching.value = false
                            }
                        }
'''
s = s[:start] + new_block + s[end:]
p.write_text(s)
