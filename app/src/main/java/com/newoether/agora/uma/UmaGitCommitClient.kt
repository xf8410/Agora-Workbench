package com.newoether.agora.uma

import com.newoether.agora.github.GitHubApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal val UMA_GIT_SHA_PATTERN = Regex("[0-9a-fA-F]{40}")

internal fun requireUmaWorkbenchBranch(branch: String): String {
    require(branch.startsWith("workbench/")) { "session uploads require a workbench/* branch" }
    require(branch.length in 11..200 && ".." !in branch) { "invalid workbench branch" }
    require(branch.none { it == '\r' || it == '\n' || it == '\u0000' }) {
        "branch contains control characters"
    }
    return branch
}

internal fun buildUmaGitCommitBody(
    message: String,
    treeSha: String,
    parentSha: String,
): JsonObject {
    require(message.isNotBlank() && message.length <= 500) { "commit message must be 1-500 characters" }
    require(UMA_GIT_SHA_PATTERN.matches(treeSha)) { "invalid tree SHA" }
    require(UMA_GIT_SHA_PATTERN.matches(parentSha)) { "invalid parent SHA" }
    return buildJsonObject {
        put("message", message)
        put("tree", treeSha.lowercase())
        put("parents", kotlinx.serialization.json.buildJsonArray { add(
            kotlinx.serialization.json.JsonPrimitive(parentSha.lowercase())
        ) })
    }
}

internal fun buildUmaGitRefUpdateBody(commitSha: String): JsonObject {
    require(UMA_GIT_SHA_PATTERN.matches(commitSha)) { "invalid commit SHA" }
    return buildJsonObject {
        put("sha", commitSha.lowercase())
        put("force", false)
    }
}

data class UmaGitBranchBase(
    val branch: String,
    val headCommitSha: String,
    val treeSha: String,
)

data class UmaGitCommitResult(
    val branch: String,
    val previousHeadSha: String,
    val commitSha: String,
    val treeSha: String,
)

/** Creates a commit for an assembled session tree and advances one verified workbench branch. */
class UmaGitCommitClient(
    private val client: GitHubApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** Branch policy hook; defaults to the session-archive workbench/* rule. */
    private val requireBranch: (String) -> String = ::requireUmaWorkbenchBranch,
) {
    suspend fun readBranchBase(repo: String, branch: String): UmaGitBranchBase =
        withContext(Dispatchers.IO) {
            val safeRepo = client.validateRepo(repo)
            val safeBranch = requireBranch(branch)
            val ref = requestObject(
                "GET",
                "/repos/$safeRepo/git/ref/heads/${client.encodeSegment(safeBranch)}",
            )
            val headSha = ref["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content.orEmpty()
            require(UMA_GIT_SHA_PATTERN.matches(headSha)) { "branch ref did not contain a valid head SHA" }
            val commit = requestObject("GET", "/repos/$safeRepo/git/commits/$headSha")
            val treeSha = commit["tree"]?.jsonObject?.get("sha")?.jsonPrimitive?.content.orEmpty()
            require(UMA_GIT_SHA_PATTERN.matches(treeSha)) { "head commit did not contain a valid tree SHA" }
            UmaGitBranchBase(safeBranch, headSha.lowercase(), treeSha.lowercase())
        }

    suspend fun commitAndAdvance(
        repo: String,
        base: UmaGitBranchBase,
        newTreeSha: String,
        message: String,
    ): UmaGitCommitResult = withContext(Dispatchers.IO) {
        val safeRepo = client.validateRepo(repo)
        val safeBranch = requireBranch(base.branch)
        require(UMA_GIT_SHA_PATTERN.matches(base.headCommitSha)) { "invalid expected head SHA" }
        require(UMA_GIT_SHA_PATTERN.matches(base.treeSha)) { "invalid base tree SHA" }
        require(UMA_GIT_SHA_PATTERN.matches(newTreeSha)) { "invalid new tree SHA" }

        val current = readBranchBase(safeRepo, safeBranch)
        require(current.headCommitSha == base.headCommitSha.lowercase()) {
            "branch head changed before session commit"
        }

        val commit = requestObject(
            "POST",
            "/repos/$safeRepo/git/commits",
            buildUmaGitCommitBody(message, newTreeSha, base.headCommitSha),
        )
        val commitSha = commit["sha"]?.jsonPrimitive?.content.orEmpty()
        require(UMA_GIT_SHA_PATTERN.matches(commitSha)) { "commit response did not contain a valid SHA" }

        val updated = client.request(
            method = "PATCH",
            path = "/repos/$safeRepo/git/refs/heads/${client.encodeSegment(safeBranch)}",
            body = buildUmaGitRefUpdateBody(commitSha),
        )
        requireSuccess(updated.code, updated.body)
        val updatedSha = json.parseToJsonElement(updated.body).jsonObject["object"]
            ?.jsonObject?.get("sha")?.jsonPrimitive?.content.orEmpty()
        require(updatedSha.equals(commitSha, ignoreCase = true)) {
            "updated branch ref did not match the created commit"
        }

        UmaGitCommitResult(
            branch = safeBranch,
            previousHeadSha = base.headCommitSha.lowercase(),
            commitSha = commitSha.lowercase(),
            treeSha = newTreeSha.lowercase(),
        )
    }

    private suspend fun requestObject(method: String, path: String, body: JsonObject? = null): JsonObject {
        val response = client.request(method, path, body)
        requireSuccess(response.code, response.body)
        return json.parseToJsonElement(response.body).jsonObject
    }

    private fun requireSuccess(code: Int, body: String) {
        if (code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub API error"
            error("$message (HTTP $code)")
        }
    }
}
