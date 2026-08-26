package com.newoether.agora.github

import com.newoether.agora.viewmodel.GitHubMutationConfirmation
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class RepositoryPermissionSnapshot(
    val repository: String,
    val canPull: Boolean,
    val canPush: Boolean,
    val canAdmin: Boolean,
    val isFork: Boolean,
    val parentRepository: String? = null,
    val sourceRepository: String? = null,
)

@Serializable
data class RepositoryBranchRef(val name: String, val sha: String)

@Serializable
data class CrossForkPullRequestResult(
    val number: Long,
    val htmlUrl: String,
    val sourceRepository: String,
    val sourceBranch: String,
    val sourceSha: String,
    val targetRepository: String,
    val targetBranch: String,
)

/**
 * Complete GitHub workspace operation boundary. Every mutation confirms the exact repository,
 * refs and SHA before writing; read operations never prompt. UI and model tools share this class.
 */
class GitHubWorkspaceClient(private val client: GitHubApiClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun permissions(repository: String): RepositoryPermissionSnapshot {
        val repo = client.validateRepo(repository)
        val value = getObject("/repos/$repo")
        val permissions = value["permissions"] as? JsonObject
        return RepositoryPermissionSnapshot(
            repository = value.string("full_name", repo),
            canPull = permissions?.boolean("pull") ?: true,
            canPush = permissions?.boolean("push") ?: false,
            canAdmin = permissions?.boolean("admin") ?: false,
            isFork = value.boolean("fork"),
            parentRepository = (value["parent"] as? JsonObject)?.string("full_name")?.ifBlank { null },
            sourceRepository = (value["source"] as? JsonObject)?.string("full_name")?.ifBlank { null },
        )
    }

    suspend fun branches(repository: String): List<RepositoryBranchRef> {
        val repo = client.validateRepo(repository)
        val all = mutableListOf<RepositoryBranchRef>()
        var page = 1
        while (true) {
            val response = client.request("GET", "/repos/$repo/branches?per_page=100&page=$page")
            success(response)
            val batch = json.parseToJsonElement(response.body).jsonArray.map { item ->
                val branch = item.jsonObject
                RepositoryBranchRef(
                    branch.string("name"),
                    (branch["commit"] as? JsonObject)?.string("sha").orEmpty(),
                )
            }
            if (batch.isEmpty()) break
            all += batch
            if (!hasNextPage(response.linkHeader)) break
            page++
        }
        return all
    }

    suspend fun createFork(upstreamRepository: String, organization: String? = null): String {
        val upstream = client.validateRepo(upstreamRepository)
        val before = getObject("/repos/$upstream")
        val owner = before["owner"]?.jsonObject?.string("login").orEmpty()
        val target = organization?.trim()?.takeIf { it.isNotEmpty() } ?: "the signed-in account"
        confirm(upstream, "Create a fork of $upstream in $target. This creates a new remote repository.")
        val response = client.request("POST", "/repos/$upstream/forks", buildJsonObject {
            organization?.trim()?.takeIf { it.isNotEmpty() }?.let { put("organization", it) }
            put("default_branch_only", false)
        })
        success(response)
        val created = json.parseToJsonElement(response.body).jsonObject
        val fullName = created.string("full_name")
        require(fullName.isNotBlank() && fullName != "$owner/") { "GitHub did not return the fork identity" }
        repeat(20) {
            val ready = client.request("GET", "/repos/$fullName")
            if (ready.code in 200..299) return fullName
            delay(1_500L)
        }
        error("Fork $fullName was created but is not ready yet")
    }

    suspend fun createBaselineBranch(
        forkRepository: String,
        branch: String,
        upstreamRepository: String,
        upstreamBranch: String,
    ): String {
        val fork = client.validateRepo(forkRepository)
        val upstream = client.validateRepo(upstreamRepository)
        validRef(branch); validRef(upstreamBranch)
        verifyForkNetwork(fork, upstream)
        val sha = refSha(upstream, upstreamBranch)
        val existing = client.request("GET", "/repos/$fork/git/ref/heads/${client.encodeSegment(branch)}")
        require(existing.code == 404) { "Branch $fork:$branch already exists" }
        confirm(fork, "Create baseline branch $fork:$branch from $upstream:$upstreamBranch@$sha")
        val created = client.request("POST", "/repos/$fork/git/refs", buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", sha)
        })
        success(created)
        return refSha(fork, branch).also { require(it == sha) { "Created branch SHA verification failed" } }
    }

    suspend fun syncBaselineBranch(
        forkRepository: String,
        branch: String,
        upstreamRepository: String,
        upstreamBranch: String,
        expectedForkSha: String,
        allowReset: Boolean,
    ): String {
        val fork = client.validateRepo(forkRepository)
        val upstream = client.validateRepo(upstreamRepository)
        validRef(branch); validRef(upstreamBranch); fullSha(expectedForkSha)
        verifyForkNetwork(fork, upstream)
        val actual = refSha(fork, branch)
        require(actual.equals(expectedForkSha, true)) { "Fork branch changed; refresh before syncing" }
        val target = refSha(upstream, upstreamBranch)
        if (actual == target) return target
        val compare = getObject("/repos/$fork/compare/${client.encodeSegment(actual)}...${client.encodeSegment(target)}")
        val fastForward = compare.string("status") in setOf("ahead", "identical")
        require(fastForward || allowReset) { "Branch has diverged; explicit reset approval is required" }
        val mode = if (fastForward) "fast-forward" else "RESET"
        confirm(fork, "$mode $fork:$branch from $actual to $upstream:$upstreamBranch@$target")
        require(refSha(fork, branch).equals(expectedForkSha, true)) { "Fork branch changed during confirmation" }
        val updated = client.request(
            "PATCH",
            "/repos/$fork/git/refs/heads/${client.encodeSegment(branch)}",
            buildJsonObject { put("sha", target); put("force", !fastForward) },
        )
        success(updated)
        return refSha(fork, branch).also { require(it == target) { "Synced branch SHA verification failed" } }
    }

    suspend fun createCrossForkPullRequest(
        sourceRepository: String,
        sourceBranch: String,
        expectedSourceSha: String,
        targetRepository: String,
        targetBranch: String,
        title: String,
        body: String,
        draft: Boolean,
    ): CrossForkPullRequestResult {
        val source = client.validateRepo(sourceRepository)
        val target = client.validateRepo(targetRepository)
        validWorkBranch(sourceBranch); validRef(targetBranch); fullSha(expectedSourceSha)
        require(title.trim().length in 1..200 && body.length <= 20_000)
        verifyForkNetwork(source, target)
        val sourceSha = refSha(source, sourceBranch)
        require(sourceSha.equals(expectedSourceSha, true)) { "Source branch changed; refresh before creating PR" }
        refSha(target, targetBranch)
        val owner = source.substringBefore('/')
        val head = "$owner:$sourceBranch"
        val existing = client.request(
            "GET",
            "/repos/$target/pulls?state=open&head=${client.encodeSegment(head)}&base=${client.encodeSegment(targetBranch)}&per_page=10",
        )
        success(existing)
        require(json.parseToJsonElement(existing.body).jsonArray.isEmpty()) { "An open pull request already exists for this source and target" }
        confirm(target, "Create upstream pull request $source:$sourceBranch@$sourceSha → $target:$targetBranch — ${title.trim()}")
        require(refSha(source, sourceBranch).equals(expectedSourceSha, true)) { "Source branch changed during confirmation" }
        val response = client.request("POST", "/repos/$target/pulls", buildJsonObject {
            put("title", title.trim()); put("head", head); put("base", targetBranch)
            if (body.isNotBlank()) put("body", body)
            put("draft", draft)
        })
        success(response)
        val pull = json.parseToJsonElement(response.body).jsonObject
        val returnedSource = pull["head"]?.jsonObject?.get("repo")?.jsonObject?.string("full_name").orEmpty()
        val returnedTarget = pull["base"]?.jsonObject?.get("repo")?.jsonObject?.string("full_name").orEmpty()
        require(returnedSource.equals(source, true) && returnedTarget.equals(target, true)) {
            "GitHub created the pull request in an unexpected repository"
        }
        return CrossForkPullRequestResult(
            number = pull.long("number"), htmlUrl = pull.string("html_url"),
            sourceRepository = returnedSource, sourceBranch = pull["head"]?.jsonObject?.string("ref").orEmpty(),
            sourceSha = pull["head"]?.jsonObject?.string("sha").orEmpty(),
            targetRepository = returnedTarget, targetBranch = pull["base"]?.jsonObject?.string("ref").orEmpty(),
        )
    }

    suspend fun rerunWorkflow(repository: String, runId: Long, failedOnly: Boolean) =
        actionMutation(repository, runId, if (failedOnly) "rerun-failed-jobs" else "rerun", if (failedOnly) "Rerun failed jobs" else "Rerun workflow")

    suspend fun cancelWorkflow(repository: String, runId: Long) =
        actionMutation(repository, runId, "cancel", "Cancel workflow")

    private suspend fun actionMutation(repository: String, runId: Long, suffix: String, action: String) {
        val repo = client.validateRepo(repository); require(runId > 0)
        val run = getObject("/repos/$repo/actions/runs/$runId")
        confirm(repo, "$action $repo Actions run #$runId ${run.string("name")}@${run.string("head_sha")}")
        val response = client.request("POST", "/repos/$repo/actions/runs/$runId/$suffix")
        success(response)
    }

    private suspend fun verifyForkNetwork(fork: String, upstream: String) {
        val info = permissions(fork)
        require(info.isFork) { "$fork is not a fork" }
        val roots = setOfNotNull(info.parentRepository, info.sourceRepository)
        require(upstream in roots || permissions(upstream).sourceRepository in roots) { "$fork and $upstream are not in the same fork network" }
        require(info.canPush) { "Signed-in account cannot write $fork" }
    }

    /**
     * Follows Link headers so list endpoints are never silently truncated at one page. A missing
     * or exhausted Link header ends iteration; a malformed value is treated as absent.
     */
    private fun hasNextPage(linkHeader: String?): Boolean =
        linkHeader?.contains("\"next\"") == true

    private suspend fun refSha(repo: String, branch: String): String {
        val value = getObject("/repos/$repo/git/ref/heads/${client.encodeSegment(branch)}")
        return value.getValue("object").jsonObject.string("sha").also(::fullSha)
    }

    private suspend fun getObject(path: String): JsonObject {
        val response = client.request("GET", path); success(response)
        return json.parseToJsonElement(response.body).jsonObject
    }

    private fun success(response: GitHubApiResponse) {
        if (response.code !in 200..299) error("GitHub HTTP ${response.code}: ${response.body.take(500)}")
    }

    private suspend fun confirm(repo: String, summary: String) {
        require(GitHubMutationConfirmation.confirm("$repo\n$summary")) { "GitHub mutation denied" }
    }

    private fun validRef(value: String) { require(value.matches(Regex("[A-Za-z0-9._/-]{1,200}")) && ".." !in value && !value.startsWith('/') && !value.endsWith('/')) }
    private fun validWorkBranch(value: String) { validRef(value); require(value.startsWith("workbench/")) { "Source writes require workbench/*" } }
    private fun fullSha(value: String) { require(value.matches(Regex("[0-9a-fA-F]{40}"))) { "Expected an exact 40-character SHA" } }
    private fun JsonObject.string(key: String, default: String = "") = this[key]?.jsonPrimitive?.content ?: default
    private fun JsonObject.boolean(key: String) = this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
}
