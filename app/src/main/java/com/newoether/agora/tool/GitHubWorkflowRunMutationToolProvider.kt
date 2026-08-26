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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Destructive GitHub Actions history mutations, kept behind the normal confirmation gate. */
class GitHubWorkflowRunMutationToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    /** Null fails closed. The UI must show and approve the exact run identity. */
    var confirm: (suspend (repository: String, summary: String) -> Boolean)? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = DELETE_WORKFLOW_RUN,
                description = "Delete one completed GitHub Actions workflow run after explicit user confirmation. This permanently removes the run logs and cannot be undone.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to ToolProperty("string", "Repository in owner/name form."),
                        "run_id" to ToolProperty("integer", "Positive Actions run ID. The run must already be completed."),
                    ),
                    required = listOf("repo", "run_id"),
                ),
            )
        )
    )

    override fun handles(name: String): Boolean = name == DELETE_WORKFLOW_RUN

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != DELETE_WORKFLOW_RUN) return errorJson("Unknown tool")
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in")

        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        val repoArg = (args["repo"] as? JsonPrimitive)?.content.orEmpty()
        val runId = (args["run_id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

        return try {
            deleteCompletedRun(repoArg, runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "Unable to delete workflow run")
        }
    }

    private suspend fun deleteCompletedRun(repoArg: String, runId: Long): String {
        val repo = client.validateRepo(repoArg)
        require(runId > 0L) { "run_id must be positive" }

        // Read immediately before confirmation so the user approves the exact current run.
        val read = client.request("GET", "/repos/$repo/actions/runs/$runId")
        requireSuccess(read.code, read.body)
        val run = json.parseToJsonElement(read.body).jsonObject
        val status = run.string("status")
        require(status == "completed") { "Only completed workflow runs can be deleted" }

        val summary = buildString {
            append("Permanently delete GitHub Actions run $repo#$runId")
            run.string("name").takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            run.string("head_branch").takeIf { it.isNotBlank() }?.let { append(" on ").append(it) }
            run.string("head_sha").take(12).takeIf { it.isNotBlank() }?.let { append('@').append(it) }
            run.string("conclusion").takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
            append(". Logs will be permanently removed.")
        }
        val approved = confirm?.invoke(repo, summary) ?: false
        require(approved) { "GitHub action denied or confirmation unavailable" }

        val deleted = client.request("DELETE", "/repos/$repo/actions/runs/$runId")
        requireSuccess(deleted.code, deleted.body)
        return buildJsonObject {
            put("ok", true)
            put("deleted", true)
            put("repo", repo)
            put("run_id", runId)
            put("name", run.string("name"))
            put("head_branch", run.string("head_branch"))
            put("head_sha", run.string("head_sha"))
            put("conclusion", run.string("conclusion"))
        }.toString()
    }

    private fun requireSuccess(code: Int, body: String) {
        if (code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject.string("message")
            }.getOrDefault("GitHub API error")
            error("$message (HTTP $code)")
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content.orEmpty()

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    private companion object {
        const val DELETE_WORKFLOW_RUN = "github_delete_workflow_run"
    }
}
