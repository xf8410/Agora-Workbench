package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.model.ConversationUsageSummary
import com.newoether.agora.model.UsageRequestRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Small durable append/upsert store for request usage. Usage survives process restarts. */
class SessionUsageStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("session_usage", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(UsageRequestRecord.serializer())
    private val _records = MutableStateFlow(read())
    val records: StateFlow<List<UsageRequestRecord>> = _records.asStateFlow()

    @Synchronized
    fun upsert(record: UsageRequestRecord) {
        val next = (_records.value.filterNot { it.requestId == record.requestId } + record)
            .sortedBy { it.createdAt }
        prefs.edit().putString(KEY, json.encodeToString(serializer, next)).apply()
        _records.value = next
    }

    @Synchronized
    fun deleteConversation(conversationId: String) {
        val next = _records.value.filterNot { it.conversationId == conversationId }
        prefs.edit().putString(KEY, json.encodeToString(serializer, next)).apply()
        _records.value = next
    }

    fun summary(conversationId: String): ConversationUsageSummary = ConversationUsageSummary(
        conversationId,
        _records.value.filter { it.conversationId == conversationId },
    )

    private fun read(): List<UsageRequestRecord> = try {
        json.decodeFromString(serializer, prefs.getString(KEY, null) ?: "[]")
    } catch (_: Exception) {
        emptyList()
    }

    private companion object { const val KEY = "records_v1" }
}
