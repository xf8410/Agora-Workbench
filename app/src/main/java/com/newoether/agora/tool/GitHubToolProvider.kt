package com.newoether.agora.tool

import android.content.Context
import android.util.Base64
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class GitHubToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)

    /** Required confirmation gate for GitHub mutations. Null fails closed. */
    var confirm: (suspend (repository: String, summary: String) -> Boolean)? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val names = setOf(
        "github_list_repositories", "github_read_file", "github_create_branch",
        "github_write_file", "github_get_workflow_runs", "github_dispatch_workflow",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!client.isSignedIn()) return emptyList()
        fun string(description: String) = ToolProperty("string", description)
        return listOf(
            tool("github_list_repositories", "List repositories accessible to the signed-in GitHub account.", emptyMap()),
            tool("github_read_file", "Read a UTF-8 text file from a GitHub repository.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "path" to string("Repository-relative file path."),
                "ref" to string("Branch, tag, or commit. Defaults to main."),
            ), listOf("repo", "path")),
            tool("github_create_branch", "Create a branch for changes. Prefer workbench/<task> branches instead of modifying main directly.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "branch" to string("New branch name."),
                "base" to string("Base branch. Defaults to main."),
            ), listOf("repo", "branch")),
            tool("github_write_file", "Create or update one UTF-8 text file and commit it to an existing branch.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "path" to string("Repository-relative file path."),
                "branch" to string("Target branch. Use a workbench branch by default."),
                "message" to string("Commit message."),
                "content" to string("Complete new UTF-8 file content."),
            ), listOf("repo", "path", "branch", "message", "content")),
            tool("github_get_workflow_runs", "Get recent GitHub Actions workflow runs for a repository.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "limit" to ToolProperty("integer", "Maximum runs, 1-20."),
            ), listOf("repo")),
            tool("github_dispatch_workflow", "Trigger a workflow_dispatch workflow.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "workflow" to string("Workflow file name or numeric ID."),
                "ref" to string("Git ref. Defaults to main."),
            ), listOf("repo", "workflow")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun arg(key: String, default: String = "") = (args[key] as? JsonPrimitive)?.content ?: default
        return runCatching {
            when (name) {
                "github_list_repositories" -> listRepositories()
                "github_read_file" -> readFile(arg("repo"), arg("path"), arg("ref", "main"))
                "github_create_branch" -> {
                    val repo = arg("repo")
                    val branch = arg("branch")
                    if (!confirmMutation(repo, "Create branch $branch from ${arg("base", "main")}")) deniedJson()
                    else buildJsonObject {
                        put("branch", client.createBranch(repo, branch, arg("base", "main")))
                        put("ok", true)
                    }.toString()
                }
                "github_write_file" -> {
                    val repo = arg("repo")
                    val path = arg("path")
                    val branch = arg("branch")
                    if (!confirmMutation(repo, "Write $path on $branch\nCommit: ${arg("message")}")) deniedJson()
                    else buildJsonObject {
                        put("commit_sha", client.writeFile(
                            repo, path, branch, arg("message"), arg("content")
                        ))
                        put("ok", true)
                    }.toString()
                }
                "github_get_workflow_runs" -> workflowRuns(arg("repo"), arg("limit", "10").toIntOrNull() ?: 10)
                "github_dispatch_workflow" -> {
                    val repo = arg("repo")
                    val workflow = arg("workflow")
                    val ref = arg("ref", "main")
                    if (!confirmMutation(repo, "Dispatch workflow $workflow on $ref")) deniedJson()
                    else dispatch(repo, workflow, ref)
                }
                else -> errorJson("Unknown GitHub tool")
            }
        }.getOrElse { errorJson(it.message ?: "GitHub operation failed") }
    }

    private suspend fun listRepositories(): String {
        val response = client.request("GET", "/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=50")
        if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}")
        val repos = json.parseToJsonElement(response.body).jsonArray
        return buildJsonObject {
            putJsonArray("repositories") {
                repos.forEach { item ->
                    val obj = item.jsonObject
                    add(buildJsonObject {
                        put("full_name", obj["full_name"]?.jsonPrimitive?.content ?: "")
                        put("private", obj["private"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false)
                        put("default_branch", obj["default_branch"]?.jsonPrimitive?.content ?: "main")
                    })
                }
            }
        }.toString()
    }

    private suspend fun readFile(repo: String, path: String, ref: String): String {
        val obj = client.readFile(repo, path, ref)
        val raw = obj["content"]?.jsonPrimitive?.content?.replace("\n", "") ?: ""
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        val text = bytes.toString(Charsets.UTF_8)
        val max = 100_000
        return buildJsonObject {
            put("repo", repo); put("path", path); put("ref", ref)
            put("sha", obj["sha"]?.jsonPrimitive?.content ?: "")
            put("size", obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: bytes.size.toLong())
            put("content", if (text.length <= max) text else text.take(max) + "\n…[GitHub file preview truncated]")
            put("truncated", text.length > max)
        }.toString()
    }

    private suspend fun workflowRuns(repo: String, requested: Int): String {
        val limit = requested.coerceIn(1, 20)
        val response = client.request("GET", "/repos/$repo/actions/runs?per_page=$limit")
        if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}")
        val runs = json.parseToJsonElement(response.body).jsonObject["workflow_runs"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            putJsonArray("runs") {
                runs.forEach { item ->
                    val obj = item.jsonObject
                    add(buildJsonObject {
                        put("id", obj["id"]?.jsonPrimitive?.content ?: "")
                        put("name", obj["name"]?.jsonPrimitive?.content ?: "")
                        put("status", obj["status"]?.jsonPrimitive?.content ?: "")
                        put("conclusion", obj["conclusion"]?.jsonPrimitive?.content ?: "")
                        put("head_sha", obj["head_sha"]?.jsonPrimitive?.content ?: "")
                        put("html_url", obj["html_url"]?.jsonPrimitive?.content ?: "")
                    })
                }
            }
        }.toString()
    }

    private suspend fun dispatch(repo: String, workflow: String, ref: String): String {
        val response = client.request("POST", "/repos/$repo/actions/workflows/$workflow/dispatches", buildJsonObject { put("ref", ref) })
        if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}: ${response.body.take(300)}")
        return buildJsonObject { put("ok", true); put("workflow", workflow); put("ref", ref) }.toString()
    }

    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        ))

    private suspend fun confirmMutation(repository: String, summary: String): Boolean =
        confirm?.invoke(repository, summary) ?: false

    private fun deniedJson() = buildJsonObject {
        put("ok", false)
        put("error", "GitHub action denied or confirmation unavailable")
    }.toString()

    private fun errorJson(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()

    override fun handles(name: String): Boolean = name in names
}
