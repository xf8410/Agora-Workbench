from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"marker mismatch: {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


# Bound the columns returned to the UI. The complete rows remain intact in SQLite and
# business/export snapshot queries still use getAllMessagesForConversation(). This avoids
# CursorWindow failures before Compose gets a chance to apply its own JSON preview limits.
db = "app/src/main/java/com/newoether/agora/data/local/ChatDatabase.kt"
replace(db,
'''    @Query("SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String, limit: Int = 200): Flow<List<MessageEntity>>''',
'''    @Query("""
        SELECT id, conversationId, parentId,
          CASE WHEN length(text) > :maxTextChars
            THEN substr(text, 1, :maxTextChars) || '\n\n[… message preview truncated for stability]'
            ELSE text END AS text,
          images,
          CASE WHEN thoughts IS NOT NULL AND length(thoughts) > :maxThoughtChars
            THEN substr(thoughts, 1, :maxThoughtChars) || '\n\n[… thoughts preview truncated]'
            ELSE thoughts END AS thoughts,
          thoughtTitle, tokenCount, status, participant, timestamp, thoughtTimeMs, modelName,
          CASE WHEN toolCallJson IS NOT NULL AND length(toolCallJson) > :maxToolJsonChars
            THEN NULL ELSE toolCallJson END AS toolCallJson,
          CASE WHEN attachmentMeta IS NOT NULL AND length(attachmentMeta) > :maxAttachmentMetaChars
            THEN NULL ELSE attachmentMeta END AS attachmentMeta
        FROM (
          SELECT * FROM messages
          WHERE conversationId = :conversationId
          ORDER BY timestamp DESC LIMIT :limit
        )
        ORDER BY timestamp ASC
    """)
    fun getMessagesForConversation(
        conversationId: String,
        limit: Int = 200,
        maxTextChars: Int = 65536,
        maxThoughtChars: Int = 32768,
        maxToolJsonChars: Int = 131072,
        maxAttachmentMetaChars: Int = 32768,
    ): Flow<List<MessageEntity>>''')

repo = "app/src/main/java/com/newoether/agora/data/repository/ConversationRepository.kt"
replace(repo,
'''    fun getMessagesForConversation(conversationId: String, limit: Int = 100): Flow<List<MessageEntity>> =
        chatDao.getMessagesForConversation(conversationId, limit.coerceIn(1, 500))''',
'''    fun getMessagesForConversation(conversationId: String, limit: Int = 100): Flow<List<MessageEntity>> =
        chatDao.getMessagesForConversation(
            conversationId = conversationId,
            limit = limit.coerceIn(1, 500),
            maxTextChars = 65_536,
            maxThoughtChars = 32_768,
            maxToolJsonChars = 131_072,
            maxAttachmentMetaChars = 32_768,
        )''')

vm = "app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt"
replace(vm, "private const val INITIAL_MESSAGE_WINDOW = 40", "private const val INITIAL_MESSAGE_WINDOW = 24")
replace(vm, "private const val MESSAGE_WINDOW_STEP = 40", "private const val MESSAGE_WINDOW_STEP = 24")
replace(vm,
'''    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()''',
'''    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()

    private val _historyLoadError = MutableStateFlow<String?>(null)
    val historyLoadError: StateFlow<String?> = _historyLoadError.asStateFlow()''')
replace(vm,
'''                        combine(
                            messageWindowSize.flatMapLatest { limit ->
                                convRepo.getMessagesForConversation(id, limit)
                            },
                            convRepo.getMessageCountForConversation(id)
                        ) { entities, total -> entities to total }.collect { (entities, total) ->
                            _hasOlderMessages.value = entities.size < total && messageWindowSize.value < MAX_MESSAGE_WINDOW''',
'''                        combine(
                            messageWindowSize.flatMapLatest { limit ->
                                convRepo.getMessagesForConversation(id, limit)
                            },
                            convRepo.getMessageCountForConversation(id)
                        ) { entities, total -> entities to total }
                            .retryWhen { cause, attempt ->
                                if (cause is CancellationException) return@retryWhen false
                                DebugLog.e("ChatViewModel", "History load failed for $id (attempt $attempt)", cause)
                                _historyLoadError.value = cause.message ?: "Unable to load conversation history"
                                if (attempt < 2) {
                                    delay(150L * (attempt + 1))
                                    true
                                } else false
                            }
                            .catch { cause ->
                                if (cause is CancellationException) throw cause
                                DebugLog.e("ChatViewModel", "History load stopped for $id", cause)
                                _historyLoadError.value = cause.message ?: "Unable to load conversation history"
                                _allMessages.value = emptyList()
                                _hasOlderMessages.value = false
                                _isSwitching.value = false
                            }
                            .collect { (entities, total) ->
                            _historyLoadError.value = null
                            _hasOlderMessages.value = entities.size < total && messageWindowSize.value < MAX_MESSAGE_WINDOW''')
replace(vm,
'''        messageWindowSize.value = INITIAL_MESSAGE_WINDOW
        _currentConversationId.value = id''',
'''        messageWindowSize.value = INITIAL_MESSAGE_WINDOW
        _historyLoadError.value = null
        _allMessages.value = emptyList()
        _hasOlderMessages.value = false
        _currentConversationId.value = id''')

# Show an explicit recoverable state instead of an indistinguishable empty/white conversation.
ml = "app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt"
replace(ml,
'''    hasOlderMessages: Boolean = false,
    onLoadOlder: () -> Unit = {}
) {''',
'''    hasOlderMessages: Boolean = false,
    onLoadOlder: () -> Unit = {},
    loadError: String? = null,
    onRetryLoad: () -> Unit = {},
) {''')
replace(ml,
'''    Box(modifier = modifier) {
        LazyColumn(''',
'''    Box(modifier = modifier) {
        if (loadError != null && messages.list.isEmpty()) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Text("Conversation history could not be loaded")
                androidx.compose.material3.Text(
                    loadError,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                androidx.compose.material3.Button(
                    onClick = onRetryLoad,
                    modifier = Modifier.padding(top = 16.dp),
                ) { androidx.compose.material3.Text("Retry") }
            }
            return@Box
        }
        LazyColumn(''')

chat = "app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt"
replace(chat,
'''    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()''',
'''    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val historyLoadError by viewModel.historyLoadError.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()''')
replace(chat,
'''                                hasOlderMessages = hasOlderMessages,
                                onLoadOlder = viewModel::loadOlderMessages,
                                contentPadding = PaddingValues(''',
'''                                hasOlderMessages = hasOlderMessages,
                                onLoadOlder = viewModel::loadOlderMessages,
                                loadError = historyLoadError,
                                onRetryLoad = {
                                    currentConversationId?.let { id ->
                                        viewModel.createNewChat()
                                        viewModel.selectConversation(id)
                                    }
                                },
                                contentPadding = PaddingValues(''')

replace("app/build.gradle.kts",
'''        versionCode = 32
        versionName = "1.4.4-workbench"''',
'''        versionCode = 33
        versionName = "1.4.5-workbench"''')
