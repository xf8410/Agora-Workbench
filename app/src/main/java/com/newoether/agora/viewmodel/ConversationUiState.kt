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

        /**
         * Walk the conversation tree to produce the visible path.
         *
         * Message history is loaded through a bounded newest-first database window. In a large
         * conversation that window can contain several disconnected components (branches and
         * hidden tool/result chains), and the oldest component is not necessarily the active
         * visible branch. Walking only the first orphan can therefore resolve to synthetic rows
         * exclusively and leave the chat screen completely blank.
         *
         * Resolve every loaded component and keep the non-empty visible path whose newest row is
         * most recent. This preserves normal branch selection while guaranteeing that a window
         * containing user/model messages cannot render as an empty conversation.
         */
        fun resolvePath(
            allMessages: List<ChatMessage>,
            streamingMsg: ChatMessage?,
            selectedChildren: Map<String?, String>
        ): List<ChatMessage> {
            if (allMessages.isEmpty()) {
                return streamingMsg?.let(::listOf) ?: emptyList()
            }

            val messagesByParent = allMessages.groupBy { it.parentId }
                .mapValues { (_, list) -> list.sortedBy { it.timestamp } }
            val loadedIds = allMessages.asSequence().map { it.id }.toHashSet()

            fun walk(startCursor: String?): List<ChatMessage> {
                val path = mutableListOf<ChatMessage>()
                val visited = HashSet<String>()
                var cursor = startCursor

                while (true) {
                    val siblings = messagesByParent[cursor] ?: break
                    if (siblings.isEmpty()) break

                    val visibleSiblings = siblings.filterNot(::isSynthetic)
                    val selectedId = selectedChildren[cursor]
                    var selected = if (visibleSiblings.isNotEmpty()) {
                        visibleSiblings.find { it.id == selectedId } ?: visibleSiblings.last()
                    } else {
                        siblings.find { it.id == selectedId } ?: siblings.last()
                    }
                    if (!visited.add(selected.id)) break

                    if (streamingMsg != null && selected.id == streamingMsg.id) {
                        selected = streamingMsg
                    }
                    if (!isSynthetic(selected) || selected.id == streamingMsg?.id) {
                        path.add(selected)
                    }
                    cursor = selected.id
                }
                return path
            }

            // null covers a resident real root. Every missing parent identifies the entry point
            // of another component cut by the bounded Room query.
            val entryCursors = buildList<String?> {
                if (messagesByParent.containsKey(null)) add(null)
                allMessages.asSequence()
                    .mapNotNull { message -> message.parentId?.takeIf { it !in loadedIds } }
                    .distinct()
                    .forEach(::add)
            }

            var path = entryCursors.asSequence()
                .map(::walk)
                .filter { it.isNotEmpty() }
                .maxWithOrNull(
                    compareBy<List<ChatMessage>> { candidate -> candidate.maxOf { it.timestamp } }
                        .thenBy { it.size }
                )
                ?: emptyList()

            // A live overlay may not have reached Room yet. Attach it only to the selected path;
            // if the bounded window contains no visible row, still show the live response rather
            // than presenting an empty screen.
            if (streamingMsg != null && path.none { it.id == streamingMsg.id }) {
                val lastId = path.lastOrNull()?.id
                if (streamingMsg.parentId == lastId ||
                    (streamingMsg.parentId == null && path.isEmpty()) ||
                    (path.isEmpty() && streamingMsg.parentId !in loadedIds)
                ) {
                    path = path + streamingMsg
                }
            }

            // Defensive fallback for malformed/imported trees: visible records in the loaded
            // window are preferable to a blank page even when no component can be walked.
            if (path.isEmpty()) {
                path = allMessages.filterNot(::isSynthetic).sortedBy { it.timestamp }
            }
            return path
        }
    }
}
