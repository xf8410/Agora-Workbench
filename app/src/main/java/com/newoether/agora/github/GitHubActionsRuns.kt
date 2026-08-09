package com.newoether.agora.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GitHubWorkflowRunsResponse(
    @SerialName("workflow_runs") val workflowRuns: List<GitHubWorkflowRun> = emptyList(),
)

@Serializable
data class GitHubWorkflowRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    @SerialName("head_sha") val headSha: String,
    val event: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("html_url") val htmlUrl: String,
) {
    val shortSha: String get() = headSha.take(7)
}

private val actionsJson = Json { ignoreUnknownKeys = true }

/** Read recent workflow runs only when the user explicitly refreshes the settings page. */
suspend fun GitHubApiClient.recentWorkflowRuns(
    repo: String,
    limit: Int = 20,
): List<GitHubWorkflowRun> {
    require(limit in 1..100) { "Workflow run limit must be between 1 and 100" }
    val safeRepo = validateRepo(repo)
    val response = request(
        method = "GET",
        path = "/repos/$safeRepo/actions/runs?per_page=$limit",
    )
    if (response.code !in 200..299) {
        error("GitHub Actions runs request failed (HTTP ${response.code}): ${response.body}")
    }
    return actionsJson.decodeFromString<GitHubWorkflowRunsResponse>(response.body).workflowRuns
}
