package com.newoether.agora.github

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Crash-resistant, append-only archive of raw conversation records.
 *
 * Records are written locally before GitHub is attempted. Text is split into bounded UTF-8 files,
 * so no Room/CursorWindow clipping and no GitHub Contents API limit can discard the original.
 * GitHub failures remain queued and are retried by WorkManager after connectivity returns.
 */
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
        toolName: String? = null,
    ) {
        val timestamp = System.currentTimeMillis()
        val safeConversation = safe(conversationId)
        val safeMessage = safe(messageId)
        val base = "$timestamp-$safeMessage-${safe(category)}"
        val directory = File(root, "$safeConversation/${safe(category)}").also { it.mkdirs() }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = sha256(bytes)
        val partCount = maxOf(1, (bytes.size + MAX_PART_BYTES - 1) / MAX_PART_BYTES)

        // Metadata is committed last: its presence means every payload part was atomically sealed.
        for (part in 0 until partCount) {
            val from = part * MAX_PART_BYTES
            val to = minOf(bytes.size, from + MAX_PART_BYTES)
            val payload = if (bytes.isEmpty()) ByteArray(0) else bytes.copyOfRange(from, to)
            atomicWrite(File(directory, "$base.part-${part + 1}-of-$partCount.txt"), payload)
        }
        val metadata = buildJsonObject {
            put("schema", 1)
            put("conversation_id", conversationId)
            put("message_id", messageId)
            if (parentId != null) put("parent_id", parentId)
            put("category", category)
            put("status", status)
            if (model != null) put("model", model)
            if (toolName != null) put("tool_name", toolName)
            put("timestamp", timestamp)
            put("utf8_bytes", bytes.size)
            put("sha256", digest)
            put("parts", partCount)
        }.toString().toByteArray(Charsets.UTF_8)
        atomicWrite(File(directory, "$base.meta.json"), metadata)
        enqueue(context)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out -> out.write(bytes); out.fd.sync() }
        check(tmp.renameTo(target)) { "Could not seal conversation archive file" }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_PART_BYTES = 700 * 1024
        @Volatile private var instance: ConversationArchiveManager? = null
        fun get(context: Context): ConversationArchiveManager = instance ?: synchronized(this) {
            instance ?: ConversationArchiveManager(context.applicationContext).also { instance = it }
        }
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ConversationArchiveSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("conversation-full-archive-sync", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Uploads sealed archive files to the private context repository and records verified commits. */
class ConversationArchiveSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val auth = GitHubAuthManager(applicationContext)
        if (auth.loadSession() == null) return@withContext Result.retry()
        val root = File(applicationContext.filesDir, "conversation_archive_queue")
        if (!root.exists()) return@withContext Result.success()
        val prefs = applicationContext.getSharedPreferences("conversation_archive_sync", Context.MODE_PRIVATE)
        val uploaded = prefs.getStringSet("uploaded", emptySet()).orEmpty().toMutableSet()
        val client = GitHubApiClient(applicationContext)
        try {
            val files = root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
            for (file in files) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative in uploaded) continue
                val content = file.readText(Charsets.UTF_8)
                client.writeFile(
                    repo = "xf8410/uma-ai-context",
                    path = "conversation-archive/$relative",
                    branch = "main",
                    message = "archive: $relative",
                    content = content,
                )
                uploaded += relative
                // Commit after every file so process death cannot forget successful uploads.
                prefs.edit().putStringSet("uploaded", uploaded.toSet()).apply()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
