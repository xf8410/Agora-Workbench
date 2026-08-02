package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants

data class ConversationUiState(
    val path: List<ChatMessage> = emptyList(),
    val allMessages: List<ChatMessage> = emptyList(),
    val streamingMsg: ChatMessage? = null,
    val isLoading: Boolean = false,
    val selectedChildren: Map<String?, String> = emptyMap()
) {
    companion object {
        private fun isSynthetic(message: ChatMessage): Boolean =
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                message.id.startsWith(Constants.RESULT_MSG_PREFIX)

        /** Resolve every loaded component so a bounded window cannot render as a blank chat. */
        fun resolvePath(
            allMessages: List<ChatMessage>,
            streamingMsg: ChatMessage?,
            selectedChildren: Map<String?, String>
        ): List<ChatMessage> {
            if (allMessages.isEmpty()) return streamingMsg?.let(::listOf) ?: emptyList()

            val messagesByParent = allMessages.groupBy { it.parentId }
                .mapValues { (_, list) -> list.sortedBy { it.timestamp } }
            val loadedIds = allMessages.asSequence().map { it.id }.toHashSet()

            fun walk(startCursor: String?): List<ChatMessage> {
                val path = mutableListOf<ChatMessage>()
                val visited = HashSet<String>()
                var cursor = startCursor
                while (true) {
                    val siblings = messagesByParent[cursor] ?: break
                    val visible = siblings.filterNot(::isSynthetic)
                    val selectedId = selectedChildren[cursor]
                    var selected = if (visible.isNotEmpty()) {
                        visible.find { it.id == selectedId } ?: visible.last()
                    } else {
                        siblings.find { it.id == selectedId } ?: siblings.last()
                    }
                    if (!visited.add(selected.id)) break
                    if (streamingMsg?.id == selected.id) selected = streamingMsg
                    if (!isSynthetic(selected) || selected.id == streamingMsg?.id) path += selected
                    cursor = selected.id
                }
                return path
            }

            val entryCursors = buildList<String?> {
                if (messagesByParent.containsKey(null)) add(null)
                allMessages.asSequence()
                    .mapNotNull { it.parentId?.takeIf { parent -> parent !in loadedIds } }
                    .distinct().forEach(::add)
            }

            var path = entryCursors.asSequence().map(::walk).filter { it.isNotEmpty() }
                .maxWithOrNull(compareBy<List<ChatMessage>> { it.maxOf { msg -> msg.timestamp } }
                    .thenBy { it.size }) ?: emptyList()

            if (streamingMsg != null && path.none { it.id == streamingMsg.id }) {
                val lastId = path.lastOrNull()?.id
                if (streamingMsg.parentId == lastId ||
                    (streamingMsg.parentId == null && path.isEmpty()) ||
                    (path.isEmpty() && streamingMsg.parentId !in loadedIds)
                ) path = path + streamingMsg
            }
            if (path.isEmpty()) path = allMessages.filterNot(::isSynthetic).sortedBy { it.timestamp }
            return path
        }
    }
}
