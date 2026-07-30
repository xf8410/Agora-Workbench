from pathlib import Path


def replace(path, old, new):
    p = Path(path)
    s = p.read_text()
    if new in s:
        return
    if old not in s:
        raise RuntimeError(f"marker mismatch: {path}: {old[:100]!r}")
    p.write_text(s.replace(old, new, 1))


db = "app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt"
replace(db, 'indices = [Index(value = ["conversationId"])],', '''indices = [
        Index(value = ["conversationId"]),
        Index(value = ["conversationId", "timestamp"]),
    ],''')
replace(db, '''    @Upsert
    suspend fun upsertConversation(conversation: ChatEntity)''', '''    /** Fix abandoned rows without reading their potentially huge payload columns. */
    @Query("""
        UPDATE messages SET status = 'STOPPED'
        WHERE conversationId = :conversationId
          AND status IN ('SENDING', 'THINKING', 'TOOL_CALLING', 'TRANSCRIBING')
    """)
    suspend fun stopStuckMessages(conversationId: String)

    @Upsert
    suspend fun upsertConversation(conversation: ChatEntity)''')
replace(db, "const val CURRENT_VERSION = 15", "const val CURRENT_VERSION = 16")
replace(db, '''            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN draftText TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN draftAttachments TEXT")
                }
            }
''', '''            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN draftText TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE conversations ADD COLUMN draftAttachments TEXT")
                }
            },
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_timestamp ON messages (conversationId, timestamp)")
                }
            }
''')

repo = "app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt"
p = Path(repo)
s = p.read_text()
if "chatDao.stopStuckMessages(conversationId)" not in s:
    a = s.index("    suspend fun fixStuckMessages(conversationId: String) {")
    b = s.index("\n    // ── Embeddings", a)
    s = s[:a] + '''    suspend fun fixStuckMessages(conversationId: String) {
        chatDao.stopStuckMessages(conversationId)
    }
''' + s[b:]
    p.write_text(s)

vm = "app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt"
replace(vm, "private const val INITIAL_MESSAGE_WINDOW = 100", "private const val INITIAL_MESSAGE_WINDOW = 40")
replace(vm, "private const val MESSAGE_WINDOW_STEP = 100", "private const val MESSAGE_WINDOW_STEP = 40")
p = Path(vm)
s = p.read_text()
old_stuck = '''                        if (!state.generating.value) {
                            val stuckMessages = convRepo.getMessagesForConversation(id).first()
                                .filter { it.status == MessageStatus.SENDING || it.status == MessageStatus.THINKING || it.status == MessageStatus.TOOL_CALLING || it.status == MessageStatus.TRANSCRIBING }

                            stuckMessages.forEach { msg ->
                                convRepo.upsertMessage(msg.copy(status = MessageStatus.STOPPED))
                            }
                        }'''
if old_stuck in s:
    s = s.replace(old_stuck, '''                        if (!state.generating.value) {
                            convRepo.fixStuckMessages(id)
                        }''', 1)
elif "convRepo.fixStuckMessages(id)" not in s:
    raise RuntimeError("stuck-message marker mismatch")

map_start = "                            val mapped = entities.map {"
map_end = "                            if (!generationMirrorStarted) {"
if map_start in s:
    a = s.index(map_start)
    b = s.index(map_end, a)
    block = '''                            // Keep formatting/JSON decoding off Main and decode tool JSON once.
                            val mapped = withContext(Dispatchers.Default) {
                                entities.map { entity ->
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
                                            ToolCallData(
                                                seg.toolName.orEmpty(),
                                                seg.toolArgs ?: "{}",
                                                SearchResultFormatter.format(seg.toolResult.orEmpty(), appContext)
                                            )
                                        },
                                        attachmentMeta = entity.attachmentMeta?.let { raw ->
                                            try { Json.decodeFromString<AttachmentMeta>(raw) }
                                            catch (_: Exception) { null }
                                        }
                                    )
                                }
                            }
                            val mappedById = mapped.associateBy { it.id }
                            _allMessages.value = mapped.map { msg ->
                                if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX) && msg.toolCall == null) {
                                    mappedById[msg.parentId]?.toolCall?.let { msg.copy(toolCall = it) } ?: msg
                                } else msg
                            }
'''
    s = s[:a] + block + s[b:]
elif "val mapped = withContext(Dispatchers.Default)" not in s:
    raise RuntimeError("message mapping marker mismatch")

old_select = '''        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SWITCH_OVERLAY_FADE_MS) // Allow overlay to fade in
            _isNewChatMode.value = false
            _branchSwitchTrigger.value = null
            messageWindowSize.value = INITIAL_MESSAGE_WINDOW
            _currentConversationId.value = id
            val conversation = convRepo.getConversation(id)
            _currentActiveModel.value = conversation?.modelId
            triggerScrollToMessage()
        }'''
new_select = '''        // Query Room immediately; animate the switching overlay concurrently.
        _isNewChatMode.value = false
        _branchSwitchTrigger.value = null
        messageWindowSize.value = INITIAL_MESSAGE_WINDOW
        _currentConversationId.value = id
        switchingJob = viewModelScope.launch {
            val conversation = convRepo.getConversation(id)
            _currentActiveModel.value = conversation?.modelId
            triggerScrollToMessage()
        }'''
if old_select in s:
    s = s.replace(old_select, new_select, 1)
elif "// Query Room immediately;" not in s:
    raise RuntimeError("conversation switch marker mismatch")
p.write_text(s)

replace("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt", '''                            delay(500)
                            this@withTimeout.cancel()''', '''                            delay(32)
                            this@withTimeout.cancel()''')

replace("app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt", '''    val currentPath = messages.list.filter { it.participant != Participant.ERROR }
    val contextStartIndex = if (currentPath.size > maxContextWindow) currentPath.size - maxContextWindow else 0
    val inContextIds = currentPath.drop(contextStartIndex).map { it.id }.toSet()

    val lastUserMessageIndex = messages.list.indexOfLast { it.participant == Participant.USER }''', '''    val inContextIds = remember(messages, maxContextWindow) {
        messages.list.filter { it.participant != Participant.ERROR }
            .takeLast(maxContextWindow.coerceAtLeast(0)).mapTo(HashSet()) { it.id }
    }
    val lastUserMessageIndex = remember(messages) {
        messages.list.indexOfLast { it.participant == Participant.USER }
    }''')

replace("app/src/main/java/com/newoether/agora/ui/chat/message/RecomposeSafeMarkdown.kt", '''    render: @Composable (text: String) -> Unit
) {
    var buf0 by remember { mutableStateOf("") }''', '''    render: @Composable (text: String) -> Unit
) {
    // Only streaming needs double buffering. Stored history renders one Markdown tree.
    if (!isStreaming) {
        render(content)
        return
    }
    var buf0 by remember { mutableStateOf("") }''')

jp = "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItemJson.kt"
p = Path(jp)
s = p.read_text()
if "MAX_STRUCTURED_JSON_CHARS" not in s:
    s = s.replace('''private fun parseJsonOrNull(text: String): JsonElement? {
    return try { Json.parseToJsonElement(text) } catch (_: Exception) { null }
}''', '''private const val MAX_STRUCTURED_JSON_CHARS = 64 * 1024
private const val MAX_PLAIN_PREVIEW_CHARS = 32 * 1024
private const val MAX_JSON_DEPTH = 12
private const val MAX_JSON_CHILDREN = 200

private fun parseJsonOrNull(text: String): JsonElement? {
    if (text.length > MAX_STRUCTURED_JSON_CHARS) return null
    // Deep nesting can throw StackOverflowError, which is not an Exception.
    return try { Json.parseToJsonElement(text) } catch (_: Throwable) { null }
}''')
    s = s.replace('''private fun JsonNodeView(json: JsonElement, depth: Int = 0) {
    when (json) {''', '''private fun JsonNodeView(json: JsonElement, depth: Int = 0) {
    if (depth >= MAX_JSON_DEPTH) {
        Text("… nested JSON truncated", style = ChatType.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    when (json) {''')
    s = s.replace("obj.entries.forEach { (key, value) ->", "obj.entries.take(MAX_JSON_CHILDREN).forEach { (key, value) ->")
    s = s.replace("arr.forEachIndexed { i, item ->", "arr.take(MAX_JSON_CHILDREN).forEachIndexed { i, item ->")
    s = s.replace("JsonObjectView(item, depth) }", "JsonObjectView(item, depth + 1) }")
    s = s.replace("JsonArrayView(item, depth) }", "JsonArrayView(item, depth + 1) }")
    old_plain = '''    } else {
        SelectionContainer {
            Text(
                text = text,
                style = ChatType.thoughtCodeLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}'''
    new_plain = '''    } else {
        val truncated = text.length > MAX_PLAIN_PREVIEW_CHARS
        val preview = if (truncated) text.take(MAX_PLAIN_PREVIEW_CHARS) else text
        SelectionContainer {
            Column {
                if (truncated) {
                    Text(
                        "Large JSON (${text.length} chars) — preview truncated for stability",
                        style = ChatType.meta,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Text(preview, style = ChatType.thoughtCodeLarge,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}'''
    if old_plain not in s:
        raise RuntimeError("large JSON preview marker mismatch")
    p.write_text(s.replace(old_plain, new_plain, 1))

replace("app/build.gradle.kts", '''        versionCode = 28
        versionName = "1.4.0-workbench"''', '''        versionCode = 29
        versionName = "1.4.1-workbench"''')
