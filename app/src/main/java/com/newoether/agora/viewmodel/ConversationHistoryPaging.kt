package com.newoether.agora.viewmodel

import com.newoether.agora.data.local.MessageEntity

/** Deterministic policy shared by keyset history loading and its unit tests. */
internal object ConversationHistoryPaging {
    private val chronologicalOrder =
        compareBy<MessageEntity> { it.timestamp }.thenBy { it.id }

    /**
     * Adds one older page to the current snapshot without duplicates. The DAO cursor is the
     * strict `(timestamp, id)` tuple of the oldest loaded row, so equal timestamps remain stable.
     */
    fun mergeOlder(
        current: List<MessageEntity>,
        olderPage: List<MessageEntity>,
    ): List<MessageEntity> {
        if (olderPage.isEmpty()) return current
        val byId = LinkedHashMap<String, MessageEntity>(current.size + olderPage.size)
        olderPage.forEach { byId.putIfAbsent(it.id, it) }
        current.forEach { byId[it.id] = it }
        return byId.values.sortedWith(chronologicalOrder)
    }

    fun hasAnotherPage(receivedCount: Int, pageSize: Int): Boolean =
        pageSize > 0 && receivedCount == pageSize
}
