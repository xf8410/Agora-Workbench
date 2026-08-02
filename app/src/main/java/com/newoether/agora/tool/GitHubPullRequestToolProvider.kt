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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Guarded pull-request mutations. Merge is fail-closed and SHA-pinned. */
class GitHubPullRequestToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        tool(
            name = CREATE_PR,
            description = "Create a pull request from an existing workbench/* branch after user confirmation. This does not merge it.",
            properties = mapOf(
                "repo" to stringProperty("Repository in owner/name form."),
                "head" to stringProperty("Existing source branch; must begin with workbench/."),
                "base" to stringProperty("Target branch; defaults to repository default."),
                "title" to stringProperty("PR title, 1-200 characters."),
                "body" to stringProperty("Optional body, max 20,000 characters."),
                "draft" to ToolProperty("boolean", "Defaults false."),
            ),
            required = listOf("repo", "head", "title"),
        ),
        tool(
            name = MERGE_PR,
            description = "Merge one non-draft pull request after explicit user confirmation and exact head-SHA verification.",
            properties = mapOf(
                "repo" to stringProperty("Repository in owner/name form."),
                "number" to ToolProperty("integer", "Positive PR number."),
                "expected_head_sha" to stringProperty("Exact 40-character SHA from github_get_pull_request."),
                "method" to stringProperty("merge, squash, or rebase; defaults squash."),
                "commit_title" to stringProperty("Optional, max 200 characters."),
            ),
            required = listOf("repo", "number", "expected_head_sha"),
        ),
    )

    override fun handles(name: String): Boolean = name == CREATE_PR || name == MERGE_PR

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }

        fun stringArg(key: String, default: String = ""): String =
            (args[key] as? JsonPrimitive)?.content ?: default
        fun intArg(key: String): Int = stringArg(key).toIntOrNull() ?: 0
        fun boolArg(key: String): Boolean = stringArg(key).toBooleanStrictOrNull() ?: false

        return try {
            when (name) {
                CREATE_PR -> createPullRequest(
                    repoArg = stringArg("repo"),
                    head = stringArg("head"),
                    baseArg = stringArg("base"),
                    title = stringArg("title"),
                    body = stringArg("body"),
                    draft = boolArg("draft"),
                )
                MERGE_PR -> mergePullRequest(
                    repoArg = stringArg("repo"),
                    number = intArg("number"),
                    expectedHeadSha = stringArg("expected_head_sha"),
                    method = stringArg("method", "squash"),
                    commitTitle = stringArg("commit_title"),
                )
                else -> errorJson("Unknown tool")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "GitHub PR operation failed")
        }
    }

    private suspend fun createPullRequest(
        repoArg: String,
        head: String,
        baseArg: String,
        title: String,
        body: String,
        draft: Boolean,
    ): String {
        val repo = validRepo(repoArg)
        requireWorkbenchBranch(head)
        require(title.trim().length in 1..200) { "PR title must be 1-200 characters" }
        require(body.length <= 20_000) { "PR body is too long" }
        val repoInfo = getObject("/repos/$repo")
        val base = baseArg.ifBlank { repoInfo.string("default_branch", "main") }
        requireValidRef(base)
        require(base != head) { "PR head and base must differ" }
        requireConfirmed("Create pull request in $repo: $head → $base — ${title.trim()}")

        val response = client.request("POST", "/repos/$repo/pulls", buildJsonObject {
            put("title", title.trim())
            put("head", head)
            put("base", base)
            if (body.isNotBlank()) put("body", body)
            put("draft", draft)
        })
        requireSuccess(response.code, response.body)
        val pull = json.parseToJsonElement(response.body).jsonObject
        val pullHead = pull["head"]?.jsonObject
        val pullBase = pull["base"]?.jsonObject
        return buildJsonObject {
            put("ok", true)
            put("number", pull.long("number"))
            put("state", pull.string("state"))
            put("draft", pull.boolean("draft"))
            put("head", pullHead?.string("ref").orEmpty())
            put("head_sha", pullHead?.string("sha").orEmpty())
            put("base", pullBase?.string("ref").orEmpty())
            put("html_url", pull.string("html_url"))
        }.toString()
    }

    private suspend fun mergePullRequest(
        repoArg: String,
        number: Int,
        expectedHeadSha: String,
        method: String,
        commitTitle: String,
    ): String {
        val repo = validRepo(repoArg)
        require(number > 0) { "Pull request number must be positive" }
        require(expectedHeadSha.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid expected head SHA" }
        require(method in setOf("merge", "squash", "rebase")) { "Invalid merge method" }
        require(commitTitle.length <= 200) { "Commit title is too long" }

        val pull = getObject("/repos/$repo/pulls/$number")
        require(pull.string("state") == "open") { "Pull request is not open" }
        require(!pull.boolean("draft")) { "Draft pull requests cannot be merged" }
        val head = requireNotNull(pull["head"] as? JsonObject) { "Pull request has no head" }
        val base = requireNotNull(pull["base"] as? JsonObject) { "Pull request has no base" }
        val headRef = head.string("ref")
        val baseRef = base.string("ref")
        val headSha = head.string("sha")
        requireWorkbenchBranch(headRef)
        require(headSha.equals(expectedHeadSha, ignoreCase = true)) { "PR head changed; read it again" }
        require(pull["mergeable"]?.jsonPrimitive?.content != "false") { "Pull request is not mergeable" }
        requireConfirmed("MERGE pull request $repo#$number: $headRef@$headSha → $baseRef using $method")

        val response = client.request("PUT", "/repos/$repo/pulls/$number/merge", buildJsonObject {
            put("sha", headSha)
            put("merge_method", method)
            if (commitTitle.isNotBlank()) put("commit_title", commitTitle)
        })
        requireSuccess(response.code, response.body)
        val result = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("ok", result.boolean("merged"))
            put("merged", result.boolean("merged"))
            put("message", result.string("message"))
            put("sha", result.string("sha"))
            put("repo", repo)
            put("number", number)
        }.toString()
    }

    private suspend fun requireConfirmed(summary: String) {
        if (!GitHubMutationConfirmation.confirm(summary)) {
            error("GitHub mutation denied or confirmation unavailable")
        }
    }

    private suspend fun getObject(path: String): JsonObject {
        val response = client.request("GET", path)
        requireSuccess(response.code, response.body)
        return json.parseToJsonElement(response.body).jsonObject
    }

    private fun requireSuccess(code: Int, body: String) {
        if (code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject.string("message")
            }.getOrDefault("GitHub API error")
            error("$message (HTTP $code)")
        }
    }

    private fun validRepo(repo: String): String {
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))) {
            "Invalid owner/name repository"
        }
        return repo
    }

    private fun requireWorkbenchBranch(branch: String) {
        require(branch.startsWith("workbench/") && branch.length in 11..200) {
            "Pull request writes require a workbench/* branch"
        }
        requireValidRef(branch)
    }

    private fun requireValidRef(ref: String) {
        require(
            ref.matches(Regex("[A-Za-z0-9._/-]{1,200}")) &&
                !ref.contains("..") && !ref.startsWith('/') && !ref.endsWith('/')
        ) { "Invalid Git ref" }
    }

    private fun JsonObject.string(key: String, default: String = ""): String =
        this[key]?.jsonPrimitive?.content ?: default
    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    private fun JsonObject.boolean(key: String): Boolean =
        this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

    private fun stringProperty(description: String) = ToolProperty("string", description)
    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String> = emptyList(),
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        )
    )

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    private companion object {
        const val CREATE_PR = "github_create_pull_request"
        const val MERGE_PR = "github_merge_pull_request"
    }
}
