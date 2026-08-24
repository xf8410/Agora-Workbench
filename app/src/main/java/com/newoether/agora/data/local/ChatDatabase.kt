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
    @TypeConverter fun fromParticipant(value: Participant) = value.name
    @TypeConverter fun toParticipant(value: String) = Participant.valueOf(value)
    @TypeConverter fun fromStatus(value: MessageStatus) = value.name
    @TypeConverter fun toStatus(value: String) = MessageStatus.valueOf(value)
    @TypeConverter fun fromStringList(value: List<String>?): String = if (value != null) Json.encodeToString(value) else ""
    @TypeConverter fun toStringList(value: String?): List<String> {
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

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val systemPrompt: String? = null,
    val modelId: String? = null,
    val cronExpr: String,
    val nextRunAt: Long,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long? = null
)

@Entity(tableName = "loops", foreignKeys = [ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)])
data class LoopEntity(
    @PrimaryKey val conversationId: String,
    val intervalMs: Long,
    val prompt: String? = null,
    val nextFireAt: Long,
    val cycleCount: Int = 0,
    val maxCycles: Int? = null,
    val active: Boolean = true,
    val revision: Long = 0L
)

@Entity(tableName = "embeddings", indices = [Index(value = ["messageId", "modelId"], unique = true)])
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val modelId: String,
    val embedding: ByteArray,
    val chunkText: String,
    val dimension: Int
) {
    override fun equals(other: Any?): Boolean = this === other || (other is EmbeddingEntity && id == other.id && messageId == other.messageId && modelId == other.modelId && embedding.contentEquals(other.embedding) && chunkText == other.chunkText && dimension == other.dimension)
    override fun hashCode(): Int = (((((id.hashCode() * 31 + messageId.hashCode()) * 31 + modelId.hashCode()) * 31 + embedding.contentHashCode()) * 31 + chunkText.hashCode()) * 31 + dimension)
}

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"]), Index(value = ["conversationId", "timestamp"])],
    foreignKeys = [ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val thoughts: String? = null,
    val thoughtTitle: String? = null,
    val tokenCount: Int = 0,
    val status: MessageStatus = MessageStatus.SUCCESS,
    val participant: Participant,
    val timestamp: Long,
    val thoughtTimeMs: Long? = null,
    val modelName: String? = null,
    val toolCallJson: String? = null,
    val attachmentMeta: String? = null
)

data class MessageListRow(
    val id: String, val conversationId: String, val parentId: String?, val text: String,
    val images: List<String>, val thoughts: String?, val thoughtTitle: String?, val tokenCount: Int,
    val status: MessageStatus, val participant: Participant, val timestamp: Long, val thoughtTimeMs: Long?,
    val modelName: String?, val toolCallSummaryJson: String?, val toolPayloadAvailable: Boolean,
    val attachmentMeta: String?,
)

data class MessageImagesProjection(val images: List<String>)

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations WHERE origin NOT LIKE 'workspace:%' AND (taskId IS NULL OR graduated = 1) ORDER BY lastUpdated DESC")
    fun getAllConversations(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM conversations WHERE taskId = :taskId ORDER BY lastUpdated DESC")
    fun getExecutionsForTask(taskId: String): Flow<List<ChatEntity>>
    @Query("SELECT * FROM conversations WHERE id = :conversationId") suspend fun getConversation(conversationId: String): ChatEntity?
    @Query("SELECT * FROM conversations WHERE id = :conversationId") fun observeConversation(conversationId: String): Flow<ChatEntity?>

    @Query("""
        SELECT id, conversationId, parentId,
          CASE WHEN length(text) > :maxTextChars THEN substr(text, 1, :maxTextChars) || '\n\n[… message preview truncated for stability]' ELSE text END AS text,
          images,
          CASE WHEN thoughts IS NOT NULL AND length(thoughts) > :maxThoughtChars THEN substr(thoughts, 1, :maxThoughtChars) || '\n\n[… thoughts preview truncated]' ELSE thoughts END AS thoughts,
          thoughtTitle, tokenCount, status, participant, timestamp, thoughtTimeMs, modelName,
          CASE WHEN toolCallJson IS NOT NULL AND length(toolCallJson) <= :maxToolSummaryChars THEN toolCallJson ELSE NULL END AS toolCallSummaryJson,
          CASE WHEN toolCallJson IS NOT NULL THEN 1 ELSE 0 END AS toolPayloadAvailable,
          CASE WHEN attachmentMeta IS NOT NULL AND length(attachmentMeta) > :maxAttachmentMetaChars THEN NULL ELSE attachmentMeta END AS attachmentMeta
        FROM (SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit)
        ORDER BY timestamp ASC
    """)
    fun getMessagesForConversation(conversationId: String, limit: Int = 200, maxTextChars: Int = 65536, maxThoughtChars: Int = 32768, maxToolSummaryChars: Int = 4096, maxAttachmentMetaChars: Int = 32768): Flow<List<MessageListRow>>

    @Query("SELECT toolCallJson FROM messages WHERE id = :messageId LIMIT 1") suspend fun getToolCallJson(messageId: String): String?
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId") fun getMessageCountForConversation(conversationId: String): Flow<Int>
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC") suspend fun getAllMessagesForConversation(conversationId: String): List<MessageEntity>
    @Query("UPDATE messages SET status = 'STOPPED' WHERE conversationId = :conversationId AND status IN ('SENDING','THINKING','TOOL_CALLING','TRANSCRIBING')") suspend fun stopStuckMessages(conversationId: String)
    @Upsert suspend fun upsertConversation(conversation: ChatEntity)
    @Upsert suspend fun upsertMessage(message: MessageEntity)
    @Query("DELETE FROM conversations WHERE id = :conversationId") suspend fun deleteConversation(conversationId: String)
    @Query("DELETE FROM messages WHERE conversationId = :conversationId") suspend fun deleteMessagesByConversation(conversationId: String)
    @Query("DELETE FROM messages WHERE id IN (:ids)") suspend fun deleteMessagesByIds(ids: List<String>)
    @Query("DELETE FROM embeddings WHERE messageId IN (SELECT id FROM messages WHERE conversationId = :conversationId)") suspend fun deleteEmbeddingsByConversation(conversationId: String)
    @Query("DELETE FROM embeddings WHERE NOT EXISTS (SELECT 1 FROM messages WHERE messages.id = embeddings.messageId)") suspend fun deleteOrphanedEmbeddings()

    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId=c.id WHERE c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND (m.text LIKE '%'||:query||'%' OR c.title LIKE '%'||:query||'%') AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' ORDER BY m.timestamp DESC LIMIT :limit") suspend fun searchMessages(query: String, limit: Int = 10): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE conversationId=:conversationId ORDER BY timestamp DESC LIMIT 1") suspend fun getLastMessageForConversation(conversationId: String): MessageEntity?
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId=c.id WHERE c.taskId=:taskId ORDER BY m.timestamp ASC") fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>>

    @Upsert suspend fun upsertEmbedding(embedding: EmbeddingEntity)
    @Query("SELECT * FROM embeddings WHERE messageId=:messageId LIMIT 1") suspend fun getEmbedding(messageId: String): EmbeddingEntity?
    @Query("SELECT * FROM embeddings") suspend fun getAllEmbeddings(): List<EmbeddingEntity>
    @Query("DELETE FROM embeddings WHERE messageId=:messageId") suspend fun deleteEmbedding(messageId: String)
    @Query("SELECT e.* FROM embeddings e INNER JOIN messages m ON e.messageId=m.id INNER JOIN conversations c ON m.conversationId=c.id WHERE e.modelId=:modelId AND c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'") suspend fun getEmbeddingsByModel(modelId: String): List<EmbeddingEntity>
    @Query("DELETE FROM embeddings WHERE modelId=:modelId") suspend fun deleteEmbeddingsByModel(modelId: String)
    @Query("SELECT COUNT(*) FROM embeddings e INNER JOIN messages m ON e.messageId=m.id INNER JOIN conversations c ON m.conversationId=c.id WHERE e.modelId=:modelId AND c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'") suspend fun getEmbeddingCountByModel(modelId: String): Int
    @Query("SELECT e.messageId FROM embeddings e INNER JOIN messages m ON e.messageId=m.id INNER JOIN conversations c ON m.conversationId=c.id WHERE e.modelId=:modelId AND c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'") suspend fun getEmbeddedMessageIdsByModel(modelId: String): List<String>
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId=c.id WHERE c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' ORDER BY m.timestamp ASC LIMIT :limit OFFSET :offset") suspend fun getMessagesForIndexingPage(limit: Int, offset: Int): List<MessageEntity>
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId=c.id WHERE c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%' AND NOT EXISTS (SELECT 1 FROM embeddings e WHERE e.messageId=m.id AND e.modelId=:modelId) ORDER BY m.timestamp ASC LIMIT :limit") suspend fun getUnembeddedMessagesPage(modelId: String, limit: Int): List<MessageEntity>
    @Query("SELECT COUNT(*) FROM messages m INNER JOIN conversations c ON m.conversationId=c.id WHERE c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'") suspend fun getIndexableMessageCount(): Int
    @Query("SELECT * FROM messages WHERE id IN (:ids)") suspend fun getMessagesByIds(ids: List<String>): List<MessageEntity>
    @Query("SELECT m.* FROM messages m INNER JOIN conversations c ON m.conversationId=c.id WHERE m.id IN (:ids) AND c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%'") suspend fun getSearchableMessagesByIds(ids: List<String>): List<MessageEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM messages m INNER JOIN conversations c ON m.conversationId=c.id WHERE m.id=:messageId AND c.origin NOT LIKE 'workspace:%' AND (c.taskId IS NULL OR c.graduated=1) AND m.participant IN ('USER','MODEL') AND m.text!='' AND m.id NOT LIKE 'tool_%' AND m.id NOT LIKE 'result_%')") suspend fun isMessageSearchable(messageId: String): Boolean
    @Transaction suspend fun upsertEmbeddingIfSearchable(embedding: EmbeddingEntity): Boolean { if (!isMessageSearchable(embedding.messageId)) { deleteEmbedding(embedding.messageId); return false }; upsertEmbedding(embedding); return true }
    @Query("SELECT * FROM conversations WHERE id=:conversationId AND origin NOT LIKE 'workspace:%' AND (taskId IS NULL OR graduated=1)") suspend fun getSearchableConversation(conversationId: String): ChatEntity?
    @Query("SELECT * FROM conversations WHERE origin NOT LIKE 'workspace:%' AND (taskId IS NULL OR graduated=1) ORDER BY lastUpdated ASC") suspend fun getSearchableConversationsList(): List<ChatEntity>
    @Query("UPDATE conversations SET draftText=:text,draftAttachments=:attachments WHERE id=:id") suspend fun updateDraft(id: String, text: String, attachments: String?)

    @Query("SELECT * FROM conversations") suspend fun getAllConversationsList(): List<ChatEntity>
    @Query("SELECT images FROM messages WHERE images!=''") suspend fun getAllMessageImages(): List<MessageImagesProjection>
    @Query("SELECT * FROM messages ORDER BY timestamp ASC LIMIT :limit OFFSET :offset") suspend fun getMessagesPage(limit: Int, offset: Int): List<MessageEntity>
    @Query("DELETE FROM conversations") suspend fun deleteAllConversations()
    @Query("SELECT id FROM messages WHERE id IN (:ids)") suspend fun findExistingMessageIds(ids: List<String>): List<String>

    @Transaction suspend fun replaceImportedConversations(tasks: List<TaskEntity>, conversations: List<ChatEntity>, messages: List<MessageEntity>, loops: List<LoopEntity>) { deleteAllLoops(); deleteAllConversations(); deleteAllTasks(); deleteOrphanedEmbeddings(); tasks.forEach { upsertTask(it) }; conversations.forEach { upsertConversation(it) }; messages.forEach { upsertMessage(it) }; loops.forEach { upsertLoop(it) } }
    @Transaction suspend fun mergeImportedConversations(tasks: List<TaskEntity>, conversations: List<ChatEntity>, messages: List<MessageEntity>, loops: List<LoopEntity>) { tasks.forEach { upsertTask(it) }; conversations.forEach { upsertConversation(it) }; val ids=findExistingMessageIds(messages.map { it.id }).toSet(); messages.filter { it.id !in ids }.forEach { upsertMessage(it) }; messages.filter { it.id in ids && it.images.isNotEmpty() }.forEach { upsertMessage(it) }; loops.forEach { upsertLoop(it) } }

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC") fun getAllTasks(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun getTask(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE enabled=1") suspend fun getEnabledTasks(): List<TaskEntity>
    @Upsert suspend fun upsertTask(task: TaskEntity)
    @Query("UPDATE tasks SET nextRunAt=:replacementNextRunAt WHERE id=:id AND enabled=1 AND cronExpr=:expectedCronExpr AND nextRunAt=:expectedNextRunAt") suspend fun updateTaskNextRunAtIfUnchanged(id: String, expectedCronExpr: String, expectedNextRunAt: Long, replacementNextRunAt: Long): Int
    @Query("DELETE FROM tasks WHERE id=:id") suspend fun deleteTask(id: String)
    @Query("DELETE FROM tasks") suspend fun deleteAllTasks()
    @Query("SELECT * FROM tasks") suspend fun getAllTasksList(): List<TaskEntity>

    @Query("SELECT * FROM loops WHERE conversationId=:conversationId") fun getLoop(conversationId: String): Flow<LoopEntity?>
    @Query("SELECT * FROM loops WHERE active=1") suspend fun getActiveLoops(): List<LoopEntity>
    @Query("SELECT * FROM loops WHERE active=1") fun observeActiveLoops(): Flow<List<LoopEntity>>
    @Upsert suspend fun upsertLoop(loop: LoopEntity)
    @Query("UPDATE loops SET nextFireAt=:replacementNextFireAt WHERE conversationId=:conversationId AND active=1 AND revision=:expectedRevision AND cycleCount=:expectedCycleCount AND intervalMs=:expectedIntervalMs AND nextFireAt=:expectedNextFireAt") suspend fun updateLoopNextFireAtIfUnchanged(conversationId: String, expectedRevision: Long, expectedCycleCount: Int, expectedIntervalMs: Long, expectedNextFireAt: Long, replacementNextFireAt: Long): Int
    @Query("UPDATE loops SET active=0,nextFireAt=0,revision=revision+1,maxCycles=:normalizedMaxCycles WHERE conversationId=:conversationId AND active=1 AND revision=:expectedRevision AND cycleCount=:expectedCycleCount AND intervalMs=:expectedIntervalMs AND nextFireAt=:expectedNextFireAt") suspend fun deactivateLoopIfUnchanged(conversationId: String, expectedRevision: Long, expectedCycleCount: Int, expectedIntervalMs: Long, expectedNextFireAt: Long, normalizedMaxCycles: Int): Int
    @Query("DELETE FROM loops WHERE conversationId=:conversationId") suspend fun deleteLoop(conversationId: String)
    @Query("DELETE FROM loops") suspend fun deleteAllLoops()
    @Query("SELECT * FROM loops") suspend fun getAllLoopsList(): List<LoopEntity>
}

@Database(entities=[ChatEntity::class,MessageEntity::class,EmbeddingEntity::class,TaskEntity::class,LoopEntity::class],version=ChatDatabase.CURRENT_VERSION,exportSchema=true)
@TypeConverters(MessageConverters::class)
abstract class ChatDatabase: RoomDatabase() {
    abstract fun chatDao(): ChatDao
    companion object {
        const val CURRENT_VERSION=16
        const val DB_NAME="agora_db"
        val ALL_MIGRATIONS=listOf(
            object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE messages ADD COLUMN images TEXT NOT NULL DEFAULT ''")}},
            object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE conversations ADD COLUMN selectedBranchesJson TEXT")}},
            object:Migration(3,4){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTimeMs INTEGER")}},
            object:Migration(4,5){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE messages ADD COLUMN modelName TEXT")}},
            object:Migration(5,6){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE conversations ADD COLUMN systemPromptId TEXT")}},
            object:Migration(6,7){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE conversations ADD COLUMN modelId TEXT")}},
            object:Migration(7,8){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE messages ADD COLUMN thoughtTitle TEXT")}},
            object:Migration(8,9){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE messages ADD COLUMN toolCallJson TEXT")}},
            object:Migration(9,10){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS embeddings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,messageId TEXT NOT NULL,embedding BLOB NOT NULL,chunkText TEXT NOT NULL,dimension INTEGER NOT NULL)");db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_messageId ON embeddings (messageId)")}},
            object:Migration(10,11){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE embeddings ADD COLUMN modelId TEXT NOT NULL DEFAULT ''");db.execSQL("DROP INDEX IF EXISTS index_embeddings_messageId");db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_messageId_modelId ON embeddings (messageId,modelId)")}},
            object:Migration(11,12){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE messages ADD COLUMN attachmentMeta TEXT")}},
            object:Migration(12,13){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE conversations ADD COLUMN taskId TEXT");db.execSQL("ALTER TABLE conversations ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'");db.execSQL("ALTER TABLE conversations ADD COLUMN graduated INTEGER NOT NULL DEFAULT 0");db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_taskId ON conversations (taskId)");db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL,prompt TEXT NOT NULL,systemPrompt TEXT,modelId TEXT,cronExpr TEXT NOT NULL,nextRunAt INTEGER NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,createdAt INTEGER NOT NULL,lastRunAt INTEGER)");db.execSQL("CREATE TABLE IF NOT EXISTS loops (conversationId TEXT PRIMARY KEY NOT NULL,intervalMs INTEGER NOT NULL,prompt TEXT,nextFireAt INTEGER NOT NULL,cycleCount INTEGER NOT NULL DEFAULT 0,maxCycles INTEGER,active INTEGER NOT NULL DEFAULT 1,FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE)")}},
            object:Migration(13,14){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE loops ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")}},
            object:Migration(14,15){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE conversations ADD COLUMN draftText TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE conversations ADD COLUMN draftAttachments TEXT")}},
            object:Migration(15,16){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_timestamp ON messages (conversationId,timestamp)")}}
        )
        fun getStoredVersion(context:Context):Int{val p=context.getDatabasePath(DB_NAME);if(!p.exists())return 0;return try{val db=SQLiteDatabase.openDatabase(p.path,null,SQLiteDatabase.OPEN_READONLY);val v=db.version;db.close();v}catch(e:Exception){0}}
        fun build(context:Context):ChatDatabase=Room.databaseBuilder(context.applicationContext,ChatDatabase::class.java,DB_NAME).addMigrations(*ALL_MIGRATIONS.toTypedArray()).fallbackToDestructiveMigration(false).build()
    }
}
