package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.viewmodel.GenerationContext
import com.newoether.agora.viewmodel.GitHubMutationConfirmation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun requireRepositoryVisibility(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized == "public" || normalized == "private") {
        "Repository visibility must be public or private"
    }
    return normalized
}

internal fun repositoryVisibilityPatch(visibility: String): JsonObject = buildJsonObject {
    put("visibility", requireRepositoryVisibility(visibility))
}

/** Guarded repository mutations plus bounded diagnostic reads that need specialized transport. */
class GitHubRepositoryMutationToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = SET_VISIBILITY,
                description = "Change a repository between public and private after explicit user confirmation. Making it public exposes its code and history.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to ToolProperty("string", "Repository in owner/name form."),
                        "visibility" to ToolProperty("string", "Target visibility: public or private."),
                    ),
                    required = listOf("repo", "visibility"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = FORK_REPOSITORY,
                description = "Fork an accessible upstream repository after explicit user confirmation.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to ToolProperty("string", "Upstream repository in owner/name form."),
                        "organization" to ToolProperty("string", "Optional destination organization. Omit to fork to the signed-in account."),
                        "name" to ToolProperty("string", "Optional destination repository name."),
                        "default_branch_only" to ToolProperty("boolean", "Fork only the upstream default branch; defaults to false."),
                    ),
                    required = listOf("repo"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = FAILED_LOGS,
                description = "Read bounded raw Actions logs for failed jobs in one workflow run. Signed redirects are downloaded without forwarding the GitHub token.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to ToolProperty("string", "Repository in owner/name form."),
                        "run_id" to ToolProperty("integer", "Positive Actions workflow run ID."),
                        "max_chars" to ToolProperty("integer", "Maximum combined log characters, 2000-60000; defaults to 20000."),
                        "max_jobs" to ToolProperty("integer", "Maximum failed jobs to read, 1-10; defaults to 5."),
                    ),
                    required = listOf("repo", "run_id"),
                ),
            ),
        ),
    )

    override fun handles(name: String): Boolean =
        name == SET_VISIBILITY || name == FORK_REPOSITORY || name == FAILED_LOGS

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun text(key: String): String = (args[key] as? JsonPrimitive)?.content.orEmpty()
        fun bool(key: String, default: Boolean): Boolean =
            text(key).toBooleanStrictOrNull() ?: default
        fun int(key: String, default: Int): Int = text(key).toIntOrNull() ?: default
        fun long(key: String): Long = text(key).toLongOrNull() ?: 0L

        return try {
            when (name) {
                SET_VISIBILITY -> setVisibility(text("repo"), text("visibility"))
                FORK_REPOSITORY -> forkRepository(
                    repoArg = text("repo"),
                    organizationArg = text("organization"),
                    nameArg = text("name"),
                    defaultBranchOnly = bool("default_branch_only", false),
                )
                FAILED_LOGS -> failedLogs(
                    repoArg = text("repo"),
                    runId = long("run_id"),
                    maxChars = int("max_chars", 20_000).coerceIn(2_000, 60_000),
                    maxJobs = int("max_jobs", 5).coerceIn(1, 10),
                )
                else -> errorJson("Unknown GitHub repository tool")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "GitHub operation failed")
        }
    }

    private suspend fun forkRepository(
        repoArg: String,
        organizationArg: String,
        nameArg: String,
        defaultBranchOnly: Boolean,
    ): String {
        val upstream = client.validateRepo(repoArg)
        val organization = organizationArg.trim()
        val name = nameArg.trim()
        if (organization.isNotEmpty()) {
            require(SAFE_NAME.matches(organization)) { "Invalid destination organization" }
        }
        if (name.isNotEmpty()) {
            require(SAFE_NAME.matches(name)) { "Invalid fork repository name" }
        }
        val destination = buildString {
            append(if (organization.isBlank()) "the signed-in account" else organization)
            if (name.isNotBlank()) append(" as $name")
        }
        if (!GitHubMutationConfirmation.confirm("Fork $upstream to $destination")) {
            error("GitHub mutation denied or confirmation unavailable")
        }
        val response = client.request(
            "POST",
            "/repos/$upstream/forks",
            buildJsonObject {
                if (organization.isNotBlank()) put("organization", organization)
                if (name.isNotBlank()) put("name", name)
                put("default_branch_only", defaultBranchOnly)
            },
        )
        requireSuccess(response.code, response.body)
        val fork = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("ok", true)
            put("upstream", upstream)
            put("full_name", fork.string("full_name"))
            put("private", fork.boolean("private"))
            put("default_branch", fork.string("default_branch", "main"))
            put("html_url", fork.string("html_url"))
            put("clone_url", fork.string("clone_url"))
            put("default_branch_only", defaultBranchOnly)
            put("pending", response.code == 202)
        }.toString()
    }

    private suspend fun failedLogs(
        repoArg: String,
        runId: Long,
        maxChars: Int,
        maxJobs: Int,
    ): String {
        val repo = client.validateRepo(repoArg)
        require(runId > 0L) { "run_id must be positive" }
        val jobsResponse = client.request("GET", "/repos/$repo/actions/runs/$runId/jobs?per_page=100")
        requireSuccess(jobsResponse.code, jobsResponse.body)
        val jobs = json.parseToJsonElement(jobsResponse.body).jsonObject["jobs"]?.jsonArray
            ?: JsonArray(emptyList())
        val failed = jobs.filter { it.jsonObject.string("conclusion") == "failure" }
        var remaining = maxChars
        var anyTruncated = failed.size > maxJobs
        val results = mutableListOf<JsonObject>()
        for (element in failed.take(maxJobs)) {
            if (remaining <= 0) {
                anyTruncated = true
                break
            }
            val job = element.jsonObject
            val jobId = job.long("id")
            if (jobId <= 0L) continue
            val budget = minOf(remaining, MAX_PER_JOB_CHARS)
            val logResponse = client.downloadRedirectedText(
                "/repos/$repo/actions/jobs/$jobId/logs",
                budget,
            )
            requireSuccess(logResponse.code, logResponse.body)
            val log = logResponse.body
            remaining -= log.length
            anyTruncated = anyTruncated || logResponse.truncated
            results += buildJsonObject {
                put("job_id", jobId)
                put("name", job.string("name"))
                put("log", log)
                put("truncated", logResponse.truncated)
            }
        }
        return buildJsonObject {
            put("repo", repo)
            put("run_id", runId)
            put("failed_job_count", failed.size)
            put("returned_job_count", results.size)
            put("truncated", anyTruncated || remaining <= 0)
            putJsonArray("failed_jobs") { results.forEach { add(it) } }
        }.toString()
    }

    private suspend fun setVisibility(repoArg: String, visibilityArg: String): String {
        val repo = client.validateRepo(repoArg)
        val visibility = requireRepositoryVisibility(visibilityArg)
        val beforeResponse = client.request("GET", "/repos/$repo")
        requireSuccess(beforeResponse.code, beforeResponse.body)
        val before = json.parseToJsonElement(beforeResponse.body).jsonObject
        val current = before["visibility"]?.jsonPrimitive?.content
            ?: if (before["private"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) "private" else "public"

        if (current == visibility) {
            return buildJsonObject {
                put("ok", true)
                put("changed", false)
                put("repo", repo)
                put("visibility", visibility)
                put("private", visibility == "private")
                put("html_url", before["html_url"]?.jsonPrimitive?.content.orEmpty())
            }.toString()
        }

        val warning = if (visibility == "public") {
            "Make $repo PUBLIC. Its code and Git history will become visible to everyone."
        } else {
            "Make $repo PRIVATE. Public forks and cached copies cannot be recalled."
        }
        if (!GitHubMutationConfirmation.confirm(warning)) {
            error("GitHub mutation denied or confirmation unavailable")
        }

        val response = client.request("PATCH", "/repos/$repo", repositoryVisibilityPatch(visibility))
        requireSuccess(response.code, response.body)
        val updated = json.parseToJsonElement(response.body).jsonObject
        val actual = updated["visibility"]?.jsonPrimitive?.content
            ?: if (updated["private"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) "private" else "public"
        check(actual == visibility) { "GitHub returned unexpected repository visibility: $actual" }
        return buildJsonObject {
            put("ok", true)
            put("changed", true)
            put("repo", repo)
            put("previous_visibility", current)
            put("visibility", actual)
            put("private", actual == "private")
            put("html_url", updated["html_url"]?.jsonPrimitive?.content.orEmpty())
        }.toString()
    }

    private fun requireSuccess(code: Int, body: String) {
        if (code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub API error"
            error("$message (HTTP $code): ${body.take(300)}")
        }
    }

    private fun JsonObject.string(key: String, default: String = ""): String =
        this[key]?.jsonPrimitive?.content ?: default

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.boolean(key: String, default: Boolean = false): Boolean =
        this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: default

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    private companion object {
        const val SET_VISIBILITY = "github_set_repository_visibility"
        const val FORK_REPOSITORY = "github_fork_repository"
        const val FAILED_LOGS = "github_get_workflow_failed_logs"
        const val MAX_PER_JOB_CHARS = 20_000
        val SAFE_NAME = Regex("[A-Za-z0-9_.-]{1,100}")
    }
}
