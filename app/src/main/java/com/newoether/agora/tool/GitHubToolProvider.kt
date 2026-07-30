package com.newoether.agora.tool

import android.content.Context
import android.util.Base64
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.github.GitHubApiResponse
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.*

/** GitHub tools exposed to the model. Credentials stay inside GitHubApiClient. */
class GitHubToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    /** Every mutating operation passes through the app confirmation dialog. */
    var confirm: (suspend (summary: String) -> Boolean)? = null

    private val names = setOf(
        "github_list_repositories", "github_get_repository", "github_create_repository",
        "github_update_repository", "github_delete_repository", "github_read_file",
        "github_write_file", "github_delete_file", "github_list_branches",
        "github_create_branch", "github_delete_branch", "github_list_pull_requests",
        "github_create_pull_request", "github_merge_pull_request", "github_get_workflow_runs",
        "github_get_workflow_run", "github_dispatch_workflow", "github_cancel_workflow_run",
        "github_rerun_workflow",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun str(description: String) = ToolProperty("string", description)
        fun bool(description: String) = ToolProperty("boolean", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            tool("github_list_repositories", "List repositories accessible to the signed-in account.", mapOf(
                "limit" to integer("Maximum repositories, 1-100. Defaults to 50."),
            )),
            tool("github_get_repository", "Get repository metadata, visibility, default branch and permissions.", mapOf(
                "repo" to str("Repository in owner/name form."),
            ), listOf("repo")),
            tool("github_create_repository", "Create a repository for the signed-in user. Private by default.", mapOf(
                "name" to str("Repository name."),
                "description" to str("Optional description."),
                "private" to bool("Whether the repository is private. Defaults to true."),
                "auto_init" to bool("Create an initial README commit. Defaults to true."),
                "gitignore_template" to str("Optional GitHub .gitignore template, for example Android or Kotlin."),
                "license_template" to str("Optional license keyword, for example mit or apache-2.0."),
            ), listOf("name")),
            tool("github_update_repository", "Update repository description, visibility, default branch, archive state or feature flags.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "description" to str("New description; omit to keep unchanged."),
                "visibility" to str("private, public, or internal; omit to keep unchanged."),
                "default_branch" to str("New default branch; omit to keep unchanged."),
                "archived" to bool("Archive or unarchive the repository; omit to keep unchanged."),
                "has_issues" to bool("Enable or disable Issues; omit to keep unchanged."),
                "has_wiki" to bool("Enable or disable Wiki; omit to keep unchanged."),
            ), listOf("repo")),
            tool("github_delete_repository", "Permanently delete a repository. Requires explicit confirmation and suitable token permission.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "confirm_name" to str("Must exactly equal repo as an additional safety check."),
            ), listOf("repo", "confirm_name")),
            tool("github_read_file", "Read a UTF-8 file or list a directory.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "path" to str("Repository-relative path; empty means root."),
                "ref" to str("Branch, tag, or commit. Empty means repository default branch."),
            ), listOf("repo", "path")),
            tool("github_write_file", "Create or update one UTF-8 file and commit it.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "path" to str("Repository-relative file path."),
                "branch" to str("Target branch."),
                "message" to str("Commit message."),
                "content" to str("Complete UTF-8 file content."),
            ), listOf("repo", "path", "branch", "message", "content")),
            tool("github_delete_file", "Delete one file and commit the deletion.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "path" to str("Repository-relative file path."),
                "branch" to str("Target branch."),
                "message" to str("Commit message."),
            ), listOf("repo", "path", "branch", "message")),
            tool("github_list_branches", "List repository branches.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "limit" to integer("Maximum branches, 1-100. Defaults to 50."),
            ), listOf("repo")),
            tool("github_create_branch", "Create a branch from another branch.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "branch" to str("New branch name."),
                "base" to str("Base branch. Empty means repository default branch."),
            ), listOf("repo", "branch")),
            tool("github_delete_branch", "Delete a non-default branch.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "branch" to str("Branch to delete."),
            ), listOf("repo", "branch")),
            tool("github_list_pull_requests", "List pull requests.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "state" to str("open, closed, or all. Defaults to open."),
                "limit" to integer("Maximum pull requests, 1-100. Defaults to 30."),
            ), listOf("repo")),
            tool("github_create_pull_request", "Create a pull request.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "title" to str("Pull request title."),
                "head" to str("Source branch."),
                "base" to str("Target branch. Empty means repository default branch."),
                "body" to str("Optional pull request body."),
                "draft" to bool("Create as draft. Defaults to false."),
            ), listOf("repo", "title", "head")),
            tool("github_merge_pull_request", "Merge a pull request after confirmation.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "number" to integer("Pull request number."),
                "merge_method" to str("merge, squash, or rebase. Defaults to squash."),
                "commit_title" to str("Optional merge commit title."),
            ), listOf("repo", "number")),
            tool("github_get_workflow_runs", "Get recent GitHub Actions workflow runs.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "limit" to integer("Maximum runs, 1-100. Defaults to 10."),
            ), listOf("repo")),
            tool("github_get_workflow_run", "Get one workflow run and its jobs/step conclusions.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "run_id" to integer("Workflow run ID."),
            ), listOf("repo", "run_id")),
            tool("github_dispatch_workflow", "Trigger a workflow_dispatch workflow.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "workflow" to str("Workflow file name or numeric ID."),
                "ref" to str("Git ref. Empty means repository default branch."),
            ), listOf("repo", "workflow")),
            tool("github_cancel_workflow_run", "Cancel an in-progress workflow run.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "run_id" to integer("Workflow run ID."),
            ), listOf("repo", "run_id")),
            tool("github_rerun_workflow", "Re-run all jobs in a workflow run.", mapOf(
                "repo" to str("Repository in owner/name form."),
                "run_id" to integer("Workflow run ID."),
                "failed_only" to bool("Re-run failed jobs only. Defaults to false."),
            ), listOf("repo", "run_id")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in. Go to Settings → GitHub Workbench.")
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return errorJson("Invalid tool arguments") }
        fun text(key: String, default: String = "") = args[key]?.jsonPrimitive?.contentOrNull ?: default
        fun int(key: String, default: Int = 0) = args[key]?.jsonPrimitive?.intOrNull ?: default
        fun bool(key: String, default: Boolean = false) = args[key]?.jsonPrimitive?.booleanOrNull ?: default
        fun present(key: String) = args.containsKey(key)
        return runCatching {
            when (name) {
                "github_list_repositories" -> listRepositories(int("limit", 50))
                "github_get_repository" -> apiJson("GET", "/repos/${text("repo")}")
                "github_create_repository" -> {
                    confirmed("Create ${if (bool("private", true)) "private" else "public"} repository ${text("name")}")
                    createRepository(text("name"), text("description"), bool("private", true), bool("auto_init", true), text("gitignore_template"), text("license_template"))
                }
                "github_update_repository" -> {
                    confirmed("Update repository ${text("repo")}")
                    updateRepository(text("repo"), args, ::present)
                }
                "github_delete_repository" -> {
                    val repo = text("repo")
                    if (text("confirm_name") != repo) error("confirm_name must exactly equal repo")
                    confirmed("PERMANENTLY DELETE repository $repo")
                    emptySuccess(client.request("DELETE", "/repos/$repo"))
                }
                "github_read_file" -> readFileOrDirectory(text("repo"), text("path"), resolveRef(text("repo"), text("ref")))
                "github_write_file" -> {
                    confirmed("Commit ${text("repo")}:${text("branch")}/${text("path")}")
                    buildJsonObject { put("commit_sha", client.writeFile(text("repo"), text("path"), text("branch"), text("message"), text("content"))); put("ok", true) }.toString()
                }
                "github_delete_file" -> {
                    confirmed("Delete ${text("repo")}:${text("branch")}/${text("path")}")
                    buildJsonObject { put("commit_sha", client.deleteFile(text("repo"), text("path"), text("branch"), text("message"))); put("ok", true) }.toString()
                }
                "github_list_branches" -> apiJson("GET", "/repos/${text("repo")}/branches?per_page=${int("limit", 50).coerceIn(1, 100)}")
                "github_create_branch" -> {
                    val base = resolveRef(text("repo"), text("base"))
                    confirmed("Create branch ${text("repo")}:${text("branch")} from $base")
                    buildJsonObject { put("branch", client.createBranch(text("repo"), text("branch"), base)); put("ok", true) }.toString()
                }
                "github_delete_branch" -> {
                    confirmed("Delete branch ${text("repo")}:${text("branch")}")
                    emptySuccess(client.request("DELETE", "/repos/${text("repo")}/git/refs/heads/${client.encodePath(text("branch"))}"))
                }
                "github_list_pull_requests" -> apiJson("GET", "/repos/${text("repo")}/pulls?state=${client.encodeSegment(text("state", "open"))}&per_page=${int("limit", 30).coerceIn(1, 100)}")
                "github_create_pull_request" -> {
                    confirmed("Create pull request ${text("repo")}: ${text("head")} → ${text("base").ifBlank { "default branch" }}")
                    val base = resolveRef(text("repo"), text("base"))
                    apiJson("POST", "/repos/${text("repo")}/pulls", buildJsonObject {
                        put("title", text("title")); put("head", text("head")); put("base", base)
                        if (text("body").isNotEmpty()) put("body", text("body")); put("draft", bool("draft"))
                    })
                }
                "github_merge_pull_request" -> {
                    confirmed("Merge pull request ${text("repo")}#${int("number")}")
                    apiJson("PUT", "/repos/${text("repo")}/pulls/${int("number")}/merge", buildJsonObject {
                        put("merge_method", text("merge_method", "squash")); if (text("commit_title").isNotEmpty()) put("commit_title", text("commit_title"))
                    })
                }
                "github_get_workflow_runs" -> workflowRuns(text("repo"), int("limit", 10))
                "github_get_workflow_run" -> workflowRun(text("repo"), int("run_id"))
                "github_dispatch_workflow" -> {
                    val ref = resolveRef(text("repo"), text("ref"))
                    confirmed("Dispatch ${text("repo")} workflow ${text("workflow")} on $ref")
                    emptySuccess(client.request("POST", "/repos/${text("repo")}/actions/workflows/${client.encodeSegment(text("workflow"))}/dispatches", buildJsonObject { put("ref", ref) }))
                }
                "github_cancel_workflow_run" -> {
                    confirmed("Cancel ${text("repo")} Actions run ${int("run_id")}")
                    emptySuccess(client.request("POST", "/repos/${text("repo")}/actions/runs/${int("run_id")}/cancel"))
                }
                "github_rerun_workflow" -> {
                    confirmed("Re-run ${text("repo")} Actions run ${int("run_id")}")
                    val endpoint = if (bool("failed_only")) "rerun-failed-jobs" else "rerun"
                    emptySuccess(client.request("POST", "/repos/${text("repo")}/actions/runs/${int("run_id")}/$endpoint"))
                }
                else -> error("Unknown GitHub tool: $name")
            }
        }.getOrElse { errorJson(it.message ?: "GitHub operation failed") }
    }

    private suspend fun confirmed(summary: String) {
        if (confirm?.invoke(summary) == false) error("Denied by user")
    }

    private suspend fun resolveRef(repo: String, requested: String): String {
        if (requested.isNotBlank()) return requested
        val response = client.request("GET", "/repos/$repo")
        client.requireSuccess(response)
        return json.parseToJsonElement(response.body).jsonObject["default_branch"]?.jsonPrimitive?.content ?: "main"
    }

    private suspend fun createRepository(name: String, description: String, private: Boolean, autoInit: Boolean, gitignore: String, license: String): String =
        apiJson("POST", "/user/repos", buildJsonObject {
            put("name", name); put("description", description); put("private", private); put("auto_init", autoInit)
            if (gitignore.isNotBlank()) put("gitignore_template", gitignore)
            if (license.isNotBlank()) put("license_template", license)
        })

    private suspend fun updateRepository(repo: String, args: Map<String, JsonElement>, present: (String) -> Boolean): String =
        apiJson("PATCH", "/repos/$repo", buildJsonObject {
            fun text(key: String) = args[key]?.jsonPrimitive?.contentOrNull.orEmpty()
            fun bool(key: String) = args[key]?.jsonPrimitive?.booleanOrNull ?: false
            if (present("description")) put("description", text("description"))
            if (present("visibility")) put("visibility", text("visibility"))
            if (present("default_branch")) put("default_branch", text("default_branch"))
            if (present("archived")) put("archived", bool("archived"))
            if (present("has_issues")) put("has_issues", bool("has_issues"))
            if (present("has_wiki")) put("has_wiki", bool("has_wiki"))
        })

    private suspend fun listRepositories(requested: Int): String =
        apiJson("GET", "/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=${requested.coerceIn(1, 100)}")

    private suspend fun readFileOrDirectory(repo: String, path: String, ref: String): String {
        val payload = client.readContent(repo, path, ref)
        return when (payload) {
            is JsonArray -> buildJsonObject {
                put("repo", repo); put("path", path); put("ref", ref); put("type", "dir")
                putJsonArray("entries") { payload.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    add(buildJsonObject {
                        put("name", item.string("name")); put("path", item.string("path")); put("type", item.string("type")); put("sha", item.string("sha"))
                        item["size"]?.jsonPrimitive?.longOrNull?.let { put("size", it) }
                    })
                } }
            }.toString()
            is JsonObject -> {
                if (payload.string("type") != "file") error("Unsupported content type: ${payload.string("type", "unknown")}")
                val raw = payload["content"]?.jsonPrimitive?.content?.replace("\n", "").orEmpty()
                val bytes = Base64.decode(raw, Base64.DEFAULT)
                val text = bytes.toString(Charsets.UTF_8)
                val max = 100_000
                buildJsonObject {
                    put("repo", repo); put("path", path); put("ref", ref); put("type", "file"); put("sha", payload.string("sha")); put("size", bytes.size)
                    put("content", if (text.length <= max) text else text.take(max) + "\n…[truncated]"); put("truncated", text.length > max)
                }.toString()
            }
            else -> error("Unexpected GitHub Contents response")
        }
    }

    private suspend fun workflowRuns(repo: String, requested: Int): String {
        val response = client.request("GET", "/repos/$repo/actions/runs?per_page=${requested.coerceIn(1, 100)}")
        client.requireSuccess(response)
        val runs = json.parseToJsonElement(response.body).jsonObject["workflow_runs"] ?: JsonArray(emptyList())
        return runs.toString()
    }

    private suspend fun workflowRun(repo: String, runId: Int): String {
        val run = client.request("GET", "/repos/$repo/actions/runs/$runId")
        client.requireSuccess(run)
        val jobs = client.request("GET", "/repos/$repo/actions/runs/$runId/jobs?per_page=100")
        client.requireSuccess(jobs)
        return buildJsonObject {
            put("run", json.parseToJsonElement(run.body)); put("jobs", json.parseToJsonElement(jobs.body).jsonObject["jobs"] ?: JsonArray(emptyList()))
        }.toString()
    }

    private suspend fun apiJson(method: String, path: String, body: JsonElement? = null): String {
        val response = client.request(method, path, body)
        client.requireSuccess(response)
        return if (response.body.isBlank()) buildJsonObject { put("ok", true) }.toString() else response.body
    }

    private fun emptySuccess(response: GitHubApiResponse): String {
        client.requireSuccess(response)
        return buildJsonObject { put("ok", true) }.toString()
    }

    private fun JsonObject.string(key: String, default: String = "") = this[key]?.jsonPrimitive?.contentOrNull ?: default
    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name = name, description = description, parameters = ToolParameters(properties = properties, required = required)))
    private fun errorJson(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()
    override fun handles(name: String): Boolean = name in names
}
