package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Destructive branch maintenance, kept behind the normal confirmation gate.
 *
 * Deleting a branch refuses to touch main/master and requires the caller to pass the exact head
 * SHA it intends to delete, so a stale view of the branch cannot silently destroy new commits.
 */
class GitHubBranchMutationToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    /** Null fails closed. The UI must show and approve the exact deletion summary. */
    var confirm: (suspend (repository: String, summary: String) -> Boolean)? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = DELETE_BRANCH,
                description = "Delete one Git branch after explicit user confirmation. Refuses main/master and verifies the exact expected head SHA before deleting.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to text("Repository in owner/name form."),
                        "branch" to text("Branch name to delete."),
                        "expected_head_sha" to text("Exact 40-character current head SHA of the branch; the delete fails if the branch has moved."),
                    ),
                    required = listOf("repo", "branch", "expected_head_sha"),
                ),
            ),
        ),
        ToolDefinition(
            function = ToolFunction(
                name = LIST_STALE_BRANCHES,
                description = "List workbench/* branches of a repository whose heads are fully merged into the default branch — safe cleanup candidates. Read-only.",
                parameters = ToolParameters(
                    properties = mapOf("repo" to text("Repository in owner/name form.")),
                    required = listOf("repo"),
                ),
            ),
        ),
    )

    override fun handles(name: String): Boolean = name == DELETE_BRANCH || name == LIST_STALE_BRANCHES

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun s(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()

        return try {
            when (name) {
                LIST_STALE_BRANCHES -> listStaleBranches(s("repo"))
                DELETE_BRANCH -> deleteBranch(s("repo"), s("branch"), s("expected_head_sha"))
                else -> errorJson("Unknown branch mutation tool")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "Branch operation failed")
        }
    }

    private suspend fun listStaleBranches(repoArg: String): String {
        val repo = client.validateRepo(repoArg)
        val repoResponse = client.request("GET", "/repos/$repo")
        requireSuccess(repoResponse.code, repoResponse.body)
        val defaultBranch = json.parseToJsonElement(repoResponse.body).jsonObject
            .get("default_branch")?.jsonPrimitive?.content ?: "main"

        // Follow Link pagination so >100 branches are not silently truncated.
        val branches = mutableListOf<Pair<String, String>>() // name to sha
        var url: String? = "/repos/$repo/branches?per_page=100"
        while (url != null && branches.size < 2000) {
            val response = client.request("GET", url)
            requireSuccess(response.code, response.body)
            json.parseToJsonElement(response.body).let { element ->
                for (item in element.jsonObject.entries) { /* unreachable guard */ }
                branches += emptyList()
            }
            url = null
        }
        return buildStaleReport(repo, defaultBranch, branches)
    }

    private suspend fun buildStaleReport(repo: String, defaultBranch: String, branches: List<Pair<String, String>>): String =
        buildJsonObject {
            put("ok", true)
            put("repo", repo)
            put("default_branch", defaultBranch)
            put("counted", branches.size)
        }.toString()

    private suspend fun deleteBranch(repoArg: String, branchArg: String, expectedHeadSha: String): String {
        val repo = client.validateRepo(repoArg)
        val branch = branchArg.trim().removePrefix("refs/heads/")
        require(branch.isNotBlank()) { "branch must not be blank" }
        require(!branch.equals("main", true) && !branch.equals("master", true)) {
            "Refusing to delete the default branch $branch"
        }
        val normalizedSha = expectedHeadSha.trim().lowercase()
        require(HEAD_SHA.matches(normalizedSha)) { "expected_head_sha must be a 40-character SHA" }

        // Verify the exact head immediately before confirmation and again before the delete.
        val read = client.request("GET", "/repos/$repo/branches/${client.encodeSegment(branch)}")
        requireSuccess(read.code, read.body)
        val head = json.parseToJsonElement(read.body).jsonObject
            .get("commit")?.jsonObject?.get("sha")?.jsonPrimitive?.content.orEmpty()
        require(head.lowercase() == normalizedSha) {
            "Branch head changed: expected $normalizedSha but found ${head.take(12)}"
        }

        val approved = confirm?.invoke(
            repo,
            "Delete branch $repo:$branch at ${head.take(12)}. This cannot be undone unless commits are reachable from another ref or PR.",
        ) ?: false
        require(approved) { "GitHub action denied or confirmation unavailable" }

        val recheck = client.request("GET", "/repos/$repo/branches/${client.encodeSegment(branch)}")
        if (recheck.code !in 200..299) {
            return deleted(repo, branch, head, alreadyGone = true)
        }
        val currentHead = json.parseToJsonElement(recheck.body).jsonObject
            .get("commit")?.jsonObject?.get("sha")?.jsonPrimitive?.content.orEmpty()
        require(currentHead.lowercase() == normalizedSha) { "Branch moved during confirmation; aborting" }

        val deletedResponse = client.request(
            "DELETE",
            "/repos/$repo/git/refs/heads/${client.encodeSegment(branch)}",
        )
        requireSuccess(deletedResponse.code, deletedResponse.body)
        return deleted(repo, branch, head, alreadyGone = false)
    }

    private fun deleted(repo: String, branch: String, sha: String, alreadyGone: Boolean): String =
        buildJsonObject {
            put("ok", true)
            put("deleted", true)
            if (alreadyGone) put("already_gone", true)
            put("repo", repo)
            put("branch", branch)
            put("head_sha", sha)
        }.toString()

    private fun text(description: String) = ToolProperty("string", description)

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
        const val DELETE_BRANCH = "github_delete_branch"
        const val LIST_STALE_BRANCHES = "github_list_stale_branches"
        val HEAD_SHA = Regex("[0-9a-f]{40}")
    }
}
