package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants

data class ConversationUiState(
    val path: List<ChatMessage> = emptyList(),
    val allMessages: List<ChatMessage> = emptyList(),
    val streamingMsg: ChatMessage? = null,
    val isLoading: Boolean = false,
    val selectedChildren: Map<String?, String> = emptyMap()
) {
    companion object {
        /** Walk the conversation tree to produce the visible path. */
        fun resolvePath(
            allMessages: List<ChatMessage>,
            streamingMsg: ChatMessage?,
            selectedChildren: Map<String?, String>
        ): List<ChatMessage> {
            val path = mutableListOf<ChatMessage>()
            val messagesByParent = allMessages.groupBy { it.parentId }
                .mapValues { (_, list) -> list.sortedBy { it.timestamp } }
            // A bounded DB window may start in the middle of a long branch. Start from the
            // earliest loaded orphan instead of requiring the root message to be resident.
            val loadedIds = allMessages.asSequence().map { it.id }.toHashSet()
            var cursor: String? = allMessages
                .filter { it.parentId != null && it.parentId !in loadedIds }
                .minByOrNull { it.timestamp }
                ?.parentId

            while (true) {
                val siblings = messagesByParent[cursor] ?: break
                if (siblings.isEmpty()) break

                val selectedId = selectedChildren[cursor]
                val visibleSiblings = siblings.filter {
                    !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
                }
                var selected = if (visibleSiblings.isNotEmpty()) {
                    visibleSiblings.find { it.id == selectedId } ?: visibleSiblings.last()
                } else {
                    siblings.find { it.id == selectedId } ?: siblings.last()
                }
                // Substitute streaming message if it matches
                if (streamingMsg != null && selected.id == streamingMsg.id) {
                    selected = streamingMsg
                }
                val isSynthetic = selected.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                    selected.id.startsWith(Constants.RESULT_MSG_PREFIX)
                if (!isSynthetic || (streamingMsg != null && selected.id == streamingMsg.id)) {
                    path.add(selected)
                }
                cursor = selected.id
            }
            // Append streaming message if not yet in path
            if (streamingMsg != null && path.none { it.id == streamingMsg.id }) {
                val lastId = path.lastOrNull()?.id
                if (streamingMsg.parentId == lastId || (streamingMsg.parentId == null && path.isEmpty())) {
                    path.add(streamingMsg)
                }
            }
            return path
        }
    }
}
