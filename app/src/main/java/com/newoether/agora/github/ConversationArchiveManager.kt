package com.newoether.agora.github

import android.content.Context
import androidx.work.*
import com.newoether.agora.AgoraApplication
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Crash-resistant append-only raw conversation archive. */
class ConversationArchiveManager private constructor(private val context: Context) {
    private val root = File(context.filesDir, "conversation_archive_queue").also { it.mkdirs() }

    @Synchronized
    fun archive(
        conversationId: String,
        messageId: String,
        parentId: String?,
        category: String,
        text: String,
        status: String,
        model: String? = null,
        sourceTimestamp: Long = System.currentTimeMillis(),
    ) {
        val directory = File(root, "${safe(conversationId)}/${safe(category)}").also { it.mkdirs() }
        val base = "$sourceTimestamp-${safe(messageId)}-${safe(category)}"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = sha256(bytes)
        // Split by Unicode characters, not arbitrary bytes, so multi-byte text is never corrupted.
        val parts = if (text.isEmpty()) listOf("") else text.chunked(MAX_PART_CHARS)
        for ((index, part) in parts.withIndex()) {
            atomicWrite(File(directory, "$base.part-${index + 1}-of-${parts.size}.txt"), part.toByteArray(Charsets.UTF_8))
        }
        val metadata = buildJsonObject {
            put("schema", 1); put("conversation_id", conversationId); put("message_id", messageId)
            if (parentId != null) put("parent_id", parentId)
            put("category", category); put("status", status); if (model != null) put("model", model)
            put("timestamp", sourceTimestamp); put("utf8_bytes", bytes.size); put("sha256", digest); put("parts", parts.size)
        }.toString().toByteArray(Charsets.UTF_8)
        atomicWrite(File(directory, "$base.meta.json"), metadata)
        enqueueSync(context)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        if (target.exists()) return // immutable/idempotent record
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out -> out.write(bytes); out.fd.sync() }
        check(tmp.renameTo(target)) { "Could not seal conversation archive file" }
    }

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        // 180k UTF-16 chars are bounded below GitHub's Contents API limit even at 4-byte UTF-8.
        private const val MAX_PART_CHARS = 180_000
        @Volatile private var instance: ConversationArchiveManager? = null
        fun get(context: Context): ConversationArchiveManager = instance ?: synchronized(this) {
            instance ?: ConversationArchiveManager(context.applicationContext).also { instance = it }
        }
        fun enqueueDatabaseScan(context: Context) {
            val request = OneTimeWorkRequestBuilder<ConversationDatabaseArchiveWorker>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("conversation-database-archive", ExistingWorkPolicy.KEEP, request)
        }
        fun enqueueSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<ConversationArchiveSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("conversation-full-archive-sync", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Scans every Room row page-by-page; UI's 40/500 window cannot hide records from this archive. */
class ConversationDatabaseArchiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? AgoraApplication ?: return@withContext Result.failure()
        val dao = app.container.chatDao
        val archive = ConversationArchiveManager.get(applicationContext)
        try {
            var offset = 0
            while (true) {
                val page = dao.getMessagesPage(100, offset)
                if (page.isEmpty()) break
                for (m in page) {
                    archive.archive(m.conversationId, m.id, m.parentId, "messages", m.text, m.status.name, m.modelName, m.timestamp)
                    m.thoughts?.let { archive.archive(m.conversationId, m.id, m.parentId, "thoughts", it, m.status.name, m.modelName, m.timestamp) }
                    m.toolCallJson?.let { archive.archive(m.conversationId, m.id, m.parentId, "tools", it, m.status.name, m.modelName, m.timestamp) }
                    m.attachmentMeta?.let { archive.archive(m.conversationId, m.id, m.parentId, "attachments", it, m.status.name, m.modelName, m.timestamp) }
                }
                offset += page.size
                if (page.size < 100) break
            }
            ConversationArchiveManager.enqueueSync(applicationContext)
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}

/** Uploads sealed archive files to the private context repository, one verified commit per file. */
class ConversationArchiveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val auth = GitHubAuthManager(applicationContext)
        if (auth.loadSession() == null) return@withContext Result.retry()
        val root = File(applicationContext.filesDir, "conversation_archive_queue")
        if (!root.exists()) return@withContext Result.success()
        val prefs = applicationContext.getSharedPreferences("conversation_archive_sync", Context.MODE_PRIVATE)
        val uploaded = prefs.getStringSet("uploaded", emptySet()).orEmpty().toMutableSet()
        val client = GitHubApiClient(applicationContext)
        try {
            for (file in root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative in uploaded) continue
                client.writeFile("xf8410/uma-ai-context", "conversation-archive/$relative", "main", "archive: $relative", file.readText(Charsets.UTF_8))
                uploaded += relative
                prefs.edit().putStringSet("uploaded", uploaded.toSet()).apply()
            }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}
