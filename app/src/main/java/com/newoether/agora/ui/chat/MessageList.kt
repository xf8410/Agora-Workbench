package com.newoether.agora.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.Participant
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.model.StableModelAliases
import com.newoether.agora.model.ToolCallDisplayModes
import com.newoether.agora.ui.chat.message.MessageItem
import com.newoether.agora.util.Constants

@Composable
fun MessageList(
    messages: StableMessageList,
    allMessages: StableMessageList = StableMessageList(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    state: LazyListState = rememberLazyListState(),
    userScrollEnabled: Boolean = true,
    isLoading: Boolean = false,
    isSwitching: Boolean = false,
    visualizeContextRollout: Boolean = false,
    toolCallDisplayMode: String = ToolCallDisplayModes.DEFAULT,
    maxContextWindow: Int = 20,
    modelAliases: StableModelAliases = StableModelAliases(),
    bottomBarHeight: androidx.compose.ui.unit.Dp = 0.dp,
    viewportHeight: Int = 0,
    messageHeights: SnapshotStateMap<String, Int> = remember { mutableStateMapOf() },
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onSwitchBranch: (String?, String, Int) -> Unit = { _, _, _ -> },
    onRegenerate: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onMediaClick: (List<String>, Int) -> Unit = { _, _ -> },
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    loadToolSegments: suspend (String) -> List<MessageSegment>? = { null },
    thoughtExpandedStates: SnapshotStateMap<String, Boolean> = remember { mutableStateMapOf() },
    hasOlderMessages: Boolean = false,
    onLoadOlder: () -> Unit = {},
    loadError: String? = null,
    onRetryLoad: () -> Unit = {},
) {
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var pendingHistoryAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(isLoading) { if (isLoading) editingMessageId = null }
    LaunchedEffect(state, hasOlderMessages) {
        snapshotFlow { state.firstVisibleItemIndex }
            .distinctUntilChanged()
            .filter { it <= 2 && hasOlderMessages && pendingHistoryAnchor == null }
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

    val inContextIds = remember(messages, maxContextWindow) {
        messages.list.filter { it.participant != Participant.ERROR }
            .takeLast(maxContextWindow.coerceAtLeast(0)).mapTo(HashSet()) { it.id }
    }
    val lastUserMessageIndex = remember(messages) {
        messages.list.indexOfLast { it.participant == Participant.USER }
    }

    val siblingsByParent = remember(allMessages) {
        allMessages.list
            .filter { !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
            .groupBy { it.parentId }
            .mapValues { (_, v) -> v.sortedBy { it.timestamp } }
    }

    // The list already has bottom contentPadding for the composer. A viewport-filling tail
    // spacer leaves a large blank region after the newest reply and also makes "scroll to bottom"
    // stop on whitespace instead of the actual final message.
    val extraPadding = 0.dp

    Box(modifier = modifier) {
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            reverseLayout = false,
            state = state,
            userScrollEnabled = userScrollEnabled
        ) {
            items(messages.list, key = { it.id }) { message ->
                val isLastMessage = messages.list.lastOrNull()?.id == message.id
                val isInContext = inContextIds.contains(message.id)
                val siblings = siblingsByParent[message.parentId].orEmpty()
                val branchIndex = siblings.indexOfFirst { it.id == message.id }
                val totalBranches = siblings.size

                Box(modifier = if (isLoading) Modifier else Modifier.animateItem(fadeInSpec = tween(250), placementSpec = null, fadeOutSpec = null)) {
                    MessageItem(
                        message = message,
                        onEdit = { id, text ->
                            onEditMessage(id, text)
                            editingMessageId = null
                        },
                        isStreaming = isLastMessage && message.participant == Participant.MODEL
                            && message.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING, MessageStatus.TRANSCRIBING),
                        isLoading = isLoading,
                        isEditingAllowed = (editingMessageId == null || editingMessageId == message.id) && !isLoading,
                        isEditing = editingMessageId == message.id,
                        isSwitching = isSwitching,
                        isInContext = isInContext,
                        modelAliases = modelAliases,
                        visualizeContextRollout = visualizeContextRollout,
                        toolCallDisplayMode = toolCallDisplayMode,
                        onStartEdit = { editingMessageId = message.id },
                        onCancelEdit = { editingMessageId = null },
                        branchIndex = branchIndex,
                        totalBranches = totalBranches,
                        onSwitchBranch = { direction -> onSwitchBranch(message.parentId, message.id, direction) },
                        onRegenerate = onRegenerate,
                        onDelete = onDelete,
                        onMediaClick = onMediaClick,
                        onFileContentClick = onFileContentClick,
                        onPdfPagesClick = onPdfPagesClick,
                        loadToolSegments = loadToolSegments,
                        onHeightChanged = { height -> messageHeights[message.id] = height },
                        thoughtExpandedStates = thoughtExpandedStates
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(extraPadding))
            }
        }
    }
}
