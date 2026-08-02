package com.newoether.agora.viewmodel

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants

/** UI projection for one conversation. */
data class ConversationUiState(
    val path: List<ChatMessage> = emptyList(),
    val allMessages: List<ChatMessage> = emptyList(),
    val streamingMsg: ChatMessage? = null,
    val isLoading: Boolean = false,
    val selectedChildren: Map<String?, String> = emptyMap()
) {
    companion object {
        private val cacheLock = Any()
        private var cachedMessages: List<ChatMessage>? = null
        private var cachedSelections: Map<String?, String>? = null
        private var cachedPath: List<ChatMessage> = emptyList()

        /**
         * Resolve the persisted tree once, then overlay the streaming row in O(path length).
         *
         * Previously every 500 ms stream frame regrouped and sorted every loaded message. On a
         * long conversation that work ran together with Markdown parsing and layout on every token
         * flush, causing visible UI jank even on a fast network. StateFlow keeps the same list/map
         * instances while only streamingMsg changes, so identity caching safely removes that hot
         * O(n log n) tree rebuild. Any Room page or branch change creates a new instance and
         * invalidates the cache.
         */
        fun resolvePath(
            allMessages: List<ChatMessage>,
            streamingMsg: ChatMessage?,
            selectedChildren: Map<String?, String>
        ): List<ChatMessage> {
            val base = synchronized(cacheLock) {
                if (cachedMessages === allMessages && cachedSelections === selectedChildren) {
                    cachedPath
                } else {
                    buildPersistedPath(allMessages, selectedChildren).also {
                        cachedMessages = allMessages
                        cachedSelections = selectedChildren
                        cachedPath = it
                    }
                }
            }
            val streaming = streamingMsg ?: return base
            val existing = base.indexOfFirst { it.id == streaming.id }
            if (existing >= 0) {
                return base.toMutableList().also { it[existing] = streaming }
            }
            val lastId = base.lastOrNull()?.id
            return if (streaming.parentId == lastId || (streaming.parentId == null && base.isEmpty())) {
                base + streaming
            } else base
        }

        private fun buildPersistedPath(
            allMessages: List<ChatMessage>,
            selectedChildren: Map<String?, String>
        ): List<ChatMessage> {
            if (allMessages.isEmpty()) return emptyList()
            val path = ArrayList<ChatMessage>()
            val byParent = allMessages.groupBy { it.parentId }
                .mapValues { (_, list) -> list.sortedBy { it.timestamp } }
            val loadedIds = allMessages.asSequence().map { it.id }.toHashSet()
            var cursor: String? = allMessages.asSequence()
                .filter { it.parentId != null && it.parentId !in loadedIds }
                .minByOrNull { it.timestamp }
                ?.parentId

            while (true) {
                val siblings = byParent[cursor] ?: break
                val selectedId = selectedChildren[cursor]
                val visible = siblings.filterNot(::isSynthetic)
                val selected = if (visible.isNotEmpty()) {
                    visible.find { it.id == selectedId } ?: visible.last()
                } else {
                    siblings.find { it.id == selectedId } ?: siblings.last()
                }
                if (!isSynthetic(selected)) path += selected
                cursor = selected.id
            }
            return path
        }

        private fun isSynthetic(message: ChatMessage): Boolean =
            message.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
                message.id.startsWith(Constants.RESULT_MSG_PREFIX)
    }
}
