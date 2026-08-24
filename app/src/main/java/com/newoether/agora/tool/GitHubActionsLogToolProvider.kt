package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Reads bounded GitHub Actions job logs without requiring a workflow patch or rerun. */
class GitHubActionsLogToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = GET_JOB_LOG,
                description = "Read a bounded diagnostic view of one GitHub Actions job log. Use the job ID returned by github_get_workflow_run_details. This reads the original log directly and does not rerun or modify the workflow.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to ToolProperty("string", "Repository in owner/name form."),
                        "job_id" to ToolProperty("integer", "Positive Actions job ID."),
                        "max_chars" to ToolProperty("integer", "Maximum diagnostic characters, 1000-200000; defaults to 50000."),
                    ),
                    required = listOf("repo", "job_id"),
                ),
            ),
        )
    )

    override fun handles(name: String): Boolean = name == GET_JOB_LOG

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != GET_JOB_LOG) return errorJson("Unknown GitHub Actions log tool")
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun text(key: String): String = (args[key] as? JsonPrimitive)?.content.orEmpty()

        return try {
            readJobLog(
                repoArg = text("repo"),
                jobId = text("job_id").toLongOrNull() ?: 0L,
                maxChars = (text("max_chars").toIntOrNull() ?: 50_000).coerceIn(1_000, 200_000),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "Unable to read Actions job log")
        }
    }

    private suspend fun readJobLog(repoArg: String, jobId: Long, maxChars: Int): String {
        val repo = client.validateRepo(repoArg)
        require(jobId > 0L) { "job_id must be positive" }

        // Read and return the verified job identity alongside the log. This prevents a stale or
        // mistyped job ID from being silently attributed to another run.
        val jobResponse = client.request("GET", "/repos/$repo/actions/jobs/$jobId")
        requireSuccess(jobResponse.code, jobResponse.body)
        val job = json.parseToJsonElement(jobResponse.body).jsonObject

        // GitHub responds with a redirect to a short-lived signed log URL. HttpURLConnection
        // follows the redirect; credentials remain inside GitHubApiClient and never enter output.
        val logResponse = client.request("GET", "/repos/$repo/actions/jobs/$jobId/logs")
        requireSuccess(logResponse.code, logResponse.body)
        val view = summarizeActionsLog(logResponse.body, maxChars)

        return buildJsonObject {
            put("ok", true)
            put("repo", repo)
            put("job_id", jobId)
            put("name", job["name"]?.jsonPrimitive?.content.orEmpty())
            put("status", job["status"]?.jsonPrimitive?.content.orEmpty())
            put("conclusion", job["conclusion"]?.jsonPrimitive?.content.orEmpty())
            put("run_id", job["run_url"]?.jsonPrimitive?.content.orEmpty().substringAfterLast('/').toLongOrNull() ?: 0L)
            put("html_url", job["html_url"]?.jsonPrimitive?.content.orEmpty())
            put("source_chars", logResponse.body.length)
            put("diagnostic", view)
        }.toString()
    }

    private fun requireSuccess(code: Int, body: String) {
        if (code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub API error"
            error("$message (HTTP $code)")
        }
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    private companion object {
        const val GET_JOB_LOG = "github_get_workflow_job_log"
    }
}

/** Keeps compiler/test errors and the terminal tail, instead of returning megabytes of setup noise. */
internal fun summarizeActionsLog(raw: String, maxChars: Int): String {
    if (raw.length <= maxChars) return raw
    val diagnosticMarkers = listOf(
        " error:", "error:", "exception", "failed", "failure:", "unresolved reference",
        "type mismatch", "assertionerror", "caused by:", "##[error]", "process completed with exit code",
    )
    val lines = raw.lineSequence().toList()
    val selected = linkedSetOf<Int>()
    lines.forEachIndexed { index, line ->
        val lower = line.lowercase()
        if (diagnosticMarkers.any { it in lower }) {
            for (nearby in (index - 3).coerceAtLeast(0)..(index + 8).coerceAtMost(lines.lastIndex)) {
                selected += nearby
            }
        }
    }
    val diagnostic = selected.joinToString("\n") { lines[it] }
    val tailBudget = (maxChars / 2).coerceAtLeast(500)
    val tail = raw.takeLast(tailBudget)
    val combined = buildString {
        append("[diagnostic excerpts]\n")
        append(diagnostic.take(maxChars - tailBudget).ifBlank { "No standard error marker found." })
        append("\n\n[terminal log tail]\n")
        append(tail)
    }
    return combined.takeLast(maxChars)
}
