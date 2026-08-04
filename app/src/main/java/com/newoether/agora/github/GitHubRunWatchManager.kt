package com.newoether.agora.github

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.newoether.agora.R
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GitHubRunWatch(
    val id: String,
    val repo: String,
    val runId: Long,
    val conversationId: String? = null,
    val expectedHeadSha: String,
    val runAttempt: Int,
    val status: String,
    val conclusion: String? = null,
    val htmlUrl: String = "",
    val createdAt: Long,
    val lastCheckedAt: Long,
    val error: String? = null,
    val notified: Boolean = false,
    val active: Boolean = true,
)

/** Persistent REST-polling watches. No webhook or public callback endpoint is used. */
class GitHubRunWatchManager(private val context: Context) {
    private val app = context.applicationContext
    private val client = GitHubApiClient(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun create(repo: String, runId: Long, conversationId: String?): GitHubRunWatch {
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))) { "Invalid owner/name repository" }
        require(runId > 0) { "run_id must be positive" }
        val remote = readRemote(repo, runId)
        val now = System.currentTimeMillis()
        val watch = GitHubRunWatch(
            id = UUID.randomUUID().toString(), repo = repo, runId = runId,
            conversationId = conversationId, expectedHeadSha = remote.headSha,
            runAttempt = remote.attempt, status = remote.status,
            conclusion = remote.conclusion, htmlUrl = remote.htmlUrl,
            createdAt = now, lastCheckedAt = now,
            active = !remote.terminal,
        )
        save(watch)
        if (remote.terminal) notifyTerminal(watch) else GitHubRunWatchWorker.enqueue(app, watch.id, 30)
        return get(watch.id) ?: watch
    }

    fun list(): List<GitHubRunWatch> = prefs.all.values.mapNotNull { raw ->
        (raw as? String)?.let { runCatching { json.decodeFromString<GitHubRunWatch>(it) }.getOrNull() }
    }.sortedByDescending { it.createdAt }

    fun get(id: String): GitHubRunWatch? = prefs.getString(id, null)?.let {
        runCatching { json.decodeFromString<GitHubRunWatch>(it) }.getOrNull()
    }

    fun cancel(id: String): GitHubRunWatch {
        val current = get(id) ?: error("Watch not found")
        val updated = current.copy(active = false, error = "cancelled locally", lastCheckedAt = System.currentTimeMillis())
        save(updated)
        GitHubRunWatchWorker.cancel(app, id)
        return updated
    }

    suspend fun poll(id: String): Boolean {
        val current = get(id) ?: return true
        if (!current.active) return true
        val now = System.currentTimeMillis()
        val updated = try {
            val remote = readRemote(current.repo, current.runId)
            // A rerun may change attempt, but a different head means this is no longer the run
            // identity the user asked to watch and must not be silently accepted.
            require(remote.headSha == current.expectedHeadSha) {
                "Run head changed: expected ${current.expectedHeadSha}, got ${remote.headSha}"
            }
            current.copy(
                runAttempt = remote.attempt, status = remote.status,
                conclusion = remote.conclusion, htmlUrl = remote.htmlUrl,
                lastCheckedAt = now, error = null, active = !remote.terminal,
            )
        } catch (e: Exception) {
            current.copy(lastCheckedAt = now, error = e.message?.take(300))
        }
        save(updated)
        if (!updated.active && updated.conclusion != null) {
            notifyTerminal(updated)
            return true
        }
        return false
    }

    private data class RemoteRun(
        val headSha: String, val attempt: Int, val status: String,
        val conclusion: String?, val htmlUrl: String,
    ) { val terminal get() = status == "completed" }

    private suspend fun readRemote(repo: String, runId: Long): RemoteRun {
        val response = client.request("GET", "/repos/$repo/actions/runs/$runId")
        if (response.code !in 200..299) error("GitHub HTTP ${response.code}: ${response.body.take(200)}")
        val obj = json.parseToJsonElement(response.body).jsonObject
        fun text(key: String) = obj[key]?.jsonPrimitive?.content.orEmpty()
        return RemoteRun(
            headSha = text("head_sha"), attempt = text("run_attempt").toIntOrNull() ?: 1,
            status = text("status"), conclusion = text("conclusion").ifBlank { null },
            htmlUrl = text("html_url"),
        )
    }

    private fun save(watch: GitHubRunWatch) {
        prefs.edit().putString(watch.id, json.encodeToString(watch)).apply()
    }

    private fun notifyTerminal(watch: GitHubRunWatch) {
        if (watch.notified) return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "GitHub Actions watches", NotificationManager.IMPORTANCE_DEFAULT))
        val result = watch.conclusion ?: watch.status
        val notification = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("GitHub Actions: $result")
            .setContentText("${watch.repo} · Run ${watch.runId} · ${watch.expectedHeadSha.take(12)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${watch.repo}\nRun ${watch.runId}, attempt ${watch.runAttempt}\nCommit ${watch.expectedHeadSha}\nConclusion: $result"))
            .setAutoCancel(true)
            .build()
        manager.notify(watch.id.hashCode(), notification)
        save(watch.copy(notified = true))
    }

    private companion object {
        const val PREFS = "github_run_watches"
        const val CHANNEL = "github_run_watches"
    }
}
