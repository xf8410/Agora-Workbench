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

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        return listOf(
            tool(CREATE_PR, "Create a pull request from an existing workbench/* branch after user confirmation. This does not merge it.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "head" to string("Existing source branch; must begin with workbench/."),
                "base" to string("Target branch; defaults to the repository default branch."),
                "title" to string("Pull request title, 1-200 characters."),
                "body" to string("Optional pull request body, bounded to 20,000 characters."),
                "draft" to ToolProperty("boolean", "Create as draft; defaults to false."),
            ), listOf("repo", "head", "title")),
            tool(MERGE_PR, "Merge one non-draft pull request after explicit user confirmation and exact head-SHA verification.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "number" to ToolProperty("integer", "Positive pull request number."),
                "expected_head_sha" to string("Exact 40-character head commit SHA read from github_get_pull_request."),
                "method" to string("Merge method: merge, squash, or rebase. Defaults to squash."),
                "commit_title" to string("Optional merge commit title, bounded to 200 characters."),
            ), listOf("repo", "number", "expected_head_sha")),
        )
    }

    override fun handles(name: String) = name == CREATE_PR || name == MERGE_PR

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in. Open Settings → GitHub Workbench.")
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return errorJson("Invalid tool arguments") }
        fun text(key: String, default: String = "") = (args[key] as? JsonPrimitive)?.content ?: default
        fun int(key: String) = text(key).toIntOrNull() ?: 0
        fun bool(key: String, default: Boolean = false) = text(key).toBooleanStrictOrNull() ?: default
        return try {
            when (name) {
                CREATE_PR -> createPullRequest(text("repo"), text("head"), text("base"), text("title"), text("body"), bool("draft"))
                MERGE_PR -> mergePullRequest(text("repo"), int("number"), text("expected_head_sha"), text("method", "squash"), text("commit_title"))
                else -> errorJson("Unknown GitHub pull-request tool")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { errorJson(e.message ?: "GitHub pull-request operation failed") }
    }

    private suspend fun createPullRequest(repo: String, head: String, baseArg: String, title: String, body: String, draft: Boolean): String {
        val r = validRepo(repo); requireWorkbenchBranch(head)
        require(title.trim().length in 1..200) { "PR title must be 1-200 characters" }
        require(body.length <= 20_000) { "PR body exceeds 20,000 characters" }
        val repoInfo = getObject("/repos/$r")
        val base = baseArg.ifBlank { repoInfo.str("default_branch", "main") }
        requireSafeRef(base, "base"); require(base != head) { "PR head and base must differ" }
        checkConfirmed("Create pull request in $r: $head → $base — ${title.trim()}")
        val response = client.request("POST", "/repos/$r/pulls", buildJsonObject {
            put("title", title.trim()); put("head", head); put("base", base)
            if (body.isNotBlank()) put("body", body); put("draft", draft)
        })
        requireSuccess(response.code, response.body)
        val pr = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("ok", true); put("number", pr.long("number")); put("state", pr.str("state")); put("title", pr.str("title")); put("draft", pr.bool("draft"))
            put("head", pr["head"]?.jsonObject?.str("ref").orEmpty()); put("head_sha", pr["head"]?.jsonObject?.str("sha").orEmpty())
            put("base", pr["base"]?.jsonObject?.str("ref").orEmpty()); put("html_url", pr.str("html_url"))
        }.toString()
    }

    private suspend fun mergePullRequest(repo: String, number: Int, expectedHeadSha: String, method: String, commitTitle: String): String {
        val r = validRepo(repo); require(number > 0) { "Pull request number must be positive" }
        require(expectedHeadSha.matches(Regex("[0-9a-fA-F]{40}"))) { "expected_head_sha must be an exact 40-character commit SHA" }
        require(method in setOf("merge", "squash", "rebase")) { "method must be merge, squash, or rebase" }
        require(commitTitle.length <= 200) { "commit_title exceeds 200 characters" }
        val pr = getObject("/repos/$r/pulls/$number")
        require(pr.str("state") == "open") { "Pull request is not open" }; require(!pr.bool("draft")) { "Draft pull requests cannot be merged" }
        val head = pr["head"]?.jsonObject ?: error("Pull request has no head"); val base = pr["base"]?.jsonObject ?: error("Pull request has no base")
        val headRef = head.str("ref"); val baseRef = base.str("ref"); val actualSha = head.str("sha")
        requireWorkbenchBranch(headRef)
        require(actualSha.equals(expectedHeadSha, ignoreCase = true)) { "Pull request head changed; read it again before merging" }
        require(pr.str("mergeable") != "false") { "Pull request is not mergeable" }
        checkConfirmed("MERGE pull request $r#$number: $headRef@$actualSha → $baseRef using $method")
        val response = client.request("PUT", "/repos/$r/pulls/$number/merge", buildJsonObject {
            put("sha", actualSha); put("merge_method", method); if (commitTitle.isNotBlank()) put("commit_title", commitTitle)
        })
        requireSuccess(response.code, response.body)
        val result = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("ok", result.bool("merged")); put("merged", result.bool("merged")); put("message", result.str("message")); put("sha", result.str("sha"))
            put("repo", r); put("number", number); put("head", headRef); put("base", baseRef)
        }.toString()
    }

    private suspend fun checkConfirmed(summary: String) {
        if (!GitHubMutationConfirmation.confirm(summary)) error("GitHub mutation denied or confirmation unavailable")
    }
    private suspend fun getObject(path: String): JsonObject { val x=client.request("GET",path); requireSuccess(x.code,x.body); return json.parseToJsonElement(x.body).jsonObject }
    private fun requireSuccess(code: Int, body: String) { if(code !in 200..299) { val m=runCatching { json.parseToJsonElement(body).jsonObject.str("message") }.getOrDefault("GitHub API error"); error("$m (HTTP $code)") } }
    private fun validRepo(repo: String): String { require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))) { "Invalid owner/name repository" }; return repo }
    private fun requireWorkbenchBranch(branch: String) { require(branch.startsWith("workbench/") && branch.length in 11..200) { "Pull-request head must be a workbench/* branch" }; requireSafeRef(branch,"head") }
    private fun requireSafeRef(ref: String, label: String) { require(ref.length in 1..200 && ref.matches(Regex("[A-Za-z0-9._/-]+")) && !ref.contains("..") && !ref.startsWith('/') && !ref.endsWith('/')) { "Invalid $label ref" } }
    private fun JsonObject.str(k:String,d:String="")=this[k]?.jsonPrimitive?.content ?: d
    private fun JsonObject.long(k:String,d:Long=0)=this[k]?.jsonPrimitive?.content?.toLongOrNull() ?: d
    private fun JsonObject.bool(k:String,d:Boolean=false)=this[k]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: d
    private fun errorJson(message:String)=buildJsonObject { put("ok",false); put("error",message.take(500)) }.toString()
    private fun tool(name:String,description:String,properties:Map<String,ToolProperty>,required:List<String>)=ToolDefinition(function=ToolFunction(name=name,description=description,parameters=ToolParameters(properties=properties,required=required)))
    private companion object { const val CREATE_PR="github_create_pull_request"; const val MERGE_PR="github_merge_pull_request" }
}
