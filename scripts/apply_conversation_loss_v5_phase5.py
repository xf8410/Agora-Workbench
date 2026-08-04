#!/usr/bin/env python3
from pathlib import Path

p = Path('app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt')
s = p.read_text()
s = s.replace(
'''                            _hasOlderMessages.value = loadedEntities.size == INITIAL_MESSAGE_WINDOW
''',
'''                            _hasOlderMessages.value = ConversationHistoryPaging.hasAnotherPage(
                                loadedEntities.size, INITIAL_MESSAGE_WINDOW
                            )
''', 1)
s = s.replace(
'''                                    val knownIds = loadedEntities.asSequence().map { it.id }.toHashSet()
                                    loadedEntities = (older.filterNot { it.id in knownIds } + loadedEntities)
                                        .sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
                                    _hasOlderMessages.value = older.size == MESSAGE_PAGE_SIZE
''',
'''                                    loadedEntities = ConversationHistoryPaging.mergeOlder(
                                        loadedEntities, older
                                    )
                                    _hasOlderMessages.value = ConversationHistoryPaging.hasAnotherPage(
                                        older.size, MESSAGE_PAGE_SIZE
                                    )
''', 1)
p.write_text(s)

p = Path('app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt')
s = p.read_text()
s = s.replace(
'''    var editingMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isLoading) { if (isLoading) editingMessageId = null }
''',
'''    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var pendingHistoryAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(isLoading) { if (isLoading) editingMessageId = null }
''', 1)
s = s.replace(
'''            .filter { it <= 2 && hasOlderMessages }
            .collect { onLoadOlder() }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
''',
'''            .filter { it <= 2 && hasOlderMessages && pendingHistoryAnchor == null }
            .collect {
                val first = state.layoutInfo.visibleItemsInfo.firstOrNull()
                val anchorId = first?.key as? String
                if (anchorId != null) {
                    pendingHistoryAnchor = anchorId to state.firstVisibleItemScrollOffset
                    onLoadOlder()
                }
            }
    }
    // Re-anchor the same stable message after older rows are prepended. This avoids the viewport
    // jumping upward by one page even on Compose versions that do not retain a key automatically.
    LaunchedEffect(allMessages, messages) {
        val (anchorId, offset) = pendingHistoryAnchor ?: return@LaunchedEffect
        val anchorIndex = messages.list.indexOfFirst { it.id == anchorId }
        if (anchorIndex >= 0) state.scrollToItem(anchorIndex, offset)
        pendingHistoryAnchor = null
    }
    LaunchedEffect(loadError, hasOlderMessages) {
        if (loadError != null || !hasOlderMessages) pendingHistoryAnchor = null
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
''', 1)
p.write_text(s)
