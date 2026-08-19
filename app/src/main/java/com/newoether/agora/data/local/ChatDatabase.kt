package com.newoether.agora.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MessageConverters {
    @TypeConverter
    fun fromParticipant(value: Participant) = value.name
    @TypeConverter
    fun toParticipant(value: String) = Participant.valueOf(value)
    @TypeConverter
    fun fromStatus(value: MessageStatus) = value.name
    @TypeConverter
    fun toStatus(value: String) = MessageStatus.valueOf(value)
    @TypeConverter
    fun fromStringList(value: List<String>?): String = if (value != null) Json.encodeToString(value) else ""
    @TypeConverter
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return try { Json.decodeFromString<List<String>>(value) } catch (_: Exception) { value.split("|||") }
    }
}

@Entity(tableName = "conversations", indices = [Index(value = ["taskId"])])
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val selectedBranchesJson: String? = null,
    val systemPromptId: String? = null,
    val modelId: String? = null,
    val taskId: String? = null,
    val origin: String = "user",
    val graduated: Boolean = false,
    val draftText: String = "",
    val draftAttachments: String? = null
)

// PATCH PLACEHOLDER