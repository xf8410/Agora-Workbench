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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class GitHubToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val names = setOf(
        "github_list_repositories", "github_create_repository", "github_read_file", "github_create_branch",
        "github_write_file", "github_get_workflow_runs", "github_dispatch_workflow",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        return listOf(
            tool("github_list_repositories", "List repositories accessible to the signed-in GitHub account.", emptyMap()),
            tool("github_create_repository", "Create a repository owned by the signed-in GitHub user. Defaults to private and initializes main with a README.", mapOf(
                "name" to string("Repository name, 1-100 characters. Do not include an owner or slash."),
                "description" to string("Optional repository description."),
                "private" to ToolProperty("boolean", "Whether the repository is private. Defaults to true."),
                "auto_init" to ToolProperty("boolean", "Initialize the repository with a README and main branch. Defaults to true."),
            ), listOf("name")),
            tool("github_read_file", "Read a UTF-8 text file or list a directory in a GitHub repository.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "path" to string("Repository-relative file or directory path. Use an empty string for the root directory."),
                "ref" to string("Branch, tag, or commit. Defaults to main."),
            ), listOf("repo", "path")),
            tool("github_create_branch", "Create a branch for changes. Prefer workbench/<task> branches instead of modifying main directly.", mapOf(
                "repo" to string("Repository in owner/name form."), "branch" to string("New branch name."),
                "base" to string("Base branch. Defaults to main.")), listOf("repo", "branch")),
            tool("github_write_file", "Create or update one UTF-8 text file and commit it to an existing branch.", mapOf(
                "repo" to string("Repository in owner/name form."), "path" to string("Repository-relative file path."),
                "branch" to string("Target branch. Use a workbench branch by default."), "message" to string("Commit message."),
                "content" to string("Complete new UTF-8 file content.")), listOf("repo", "path", "branch", "message", "content")),
            tool("github_get_workflow_runs", "Get recent GitHub Actions workflow runs for a repository.", mapOf(
                "repo" to string("Repository in owner/name form."), "limit" to ToolProperty("integer", "Maximum runs, 1-20.")), listOf("repo")),
            tool("github_dispatch_workflow", "Trigger a workflow_dispatch workflow.", mapOf(
                "repo" to string("Repository in owner/name form."), "workflow" to string("Workflow file name or numeric ID."),
                "ref" to string("Git ref. Defaults to main.")), listOf("repo", "workflow")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in. Go to Settings → GitHub Workbench to sign in with a token or Device Flow.")
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return errorJson("Invalid tool arguments") }
        fun arg(key: String, default: String = "") = (args[key] as? JsonPrimitive)?.content ?: default
        fun boolArg(key: String, default: Boolean) = (args[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
        return runCatching {
            when (name) {
                "github_list_repositories" -> listRepositories()
                "github_create_repository" -> createRepository(
                    arg("name"), arg("description"), boolArg("private", true), boolArg("auto_init", true)
                )
                "github_read_file" -> readFileOrDirectory(arg("repo"), arg("path"), arg("ref", "main"))
                "github_create_branch" -> buildJsonObject { put("branch", client.createBranch(arg("repo"), arg("branch"), arg("base", "main"))); put("ok", true) }.toString()
                "github_write_file" -> buildJsonObject {
                    put("commit_sha", client.writeFile(arg("repo"), arg("path"), arg("branch"), arg("message"), arg("content"))); put("ok", true)
                }.toString()
                "github_get_workflow_runs" -> workflowRuns(arg("repo"), arg("limit", "10").toIntOrNull() ?: 10)
                "github_dispatch_workflow" -> dispatch(arg("repo"), arg("workflow"), arg("ref", "main"))
                else -> errorJson("Unknown GitHub tool")
            }
        }.getOrElse { errorJson(it.message ?: "GitHub operation failed") }
    }

    private suspend fun createRepository(name: String, description: String, privateRepo: Boolean, autoInit: Boolean): String {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Repository name must be 1-100 characters using letters, numbers, dot, underscore, or hyphen" }
        val response = client.request("POST", "/user/repos", buildJsonObject {
            put("name", name)
            if (description.isNotBlank()) put("description", description.take(350))
            put("private", privateRepo)
            put("auto_init", autoInit)
        })
        if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}: ${response.body.take(300)}")
        val obj = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("ok", true)
            put("full_name", obj.string("full_name"))
            put("private", obj.string("private").toBooleanStrictOrNull() ?: privateRepo)
            put("default_branch", obj.string("default_branch", "main"))
            put("html_url", obj.string("html_url"))
        }.toString()
    }

    private suspend fun listRepositories(): String {
        val response = client.request("GET", "/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=50")
        if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}")
        val repos = json.parseToJsonElement(response.body).jsonArray
        return buildJsonObject { putJsonArray("repositories") { repos.forEach { item ->
            val obj = item.jsonObject
            add(buildJsonObject {
                put("full_name", obj.string("full_name")); put("private", obj.string("private").toBooleanStrictOrNull() ?: false)
                put("default_branch", obj.string("default_branch", "main"))
            })
        } } }.toString()
    }

    private suspend fun readFileOrDirectory(repo: String, path: String, ref: String): String {
        val payload: JsonElement = client.readContent(repo, path, ref)
        return when (payload) {
            is JsonArray -> buildJsonObject {
                put("repo", repo); put("path", path); put("ref", ref); put("type", "dir")
                putJsonArray("entries") {
                    payload.forEach { element ->
                        val item = element as? JsonObject ?: return@forEach
                        add(buildJsonObject {
                            put("name", item.string("name")); put("path", item.string("path")); put("type", item.string("type"))
                            put("sha", item.string("sha")); item["size"]?.jsonPrimitive?.content?.toLongOrNull()?.let { put("size", it) }
                        })
                    }
                }
            }.toString()
            is JsonObject -> {
                if (payload.string("type") != "file") error("Unsupported GitHub content type: ${payload.string("type", "unknown")}")
                val raw = payload["content"]?.jsonPrimitive?.content?.replace("\n", "") ?: ""
                val bytes = Base64.decode(raw, Base64.DEFAULT)
                val text = bytes.toString(Charsets.UTF_8)
                val max = 100_000
                buildJsonObject {
                    put("repo", repo); put("path", path); put("ref", ref); put("type", "file")
                    put("sha", payload.string("sha")); put("size", payload["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: bytes.size.toLong())
                    put("content", if (text.length <= max) text else text.take(max) + "\n…[GitHub file preview truncated]")
                    put("truncated", text.length > max)
                }.toString()
            }
            else -> error("GitHub Contents API returned neither an object nor an array")
        }
    }

    private suspend fun workflowRuns(repo: String, requested: Int): String {
        val limit = requested.coerceIn(1, 20)
        val response = client.request("GET", "/repos/$repo/actions/runs?per_page=$limit")
        if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}")
        val runs = json.parseToJsonElement(response.body).jsonObject["workflow_runs"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { putJsonArray("runs") { runs.forEach { item ->
            val obj = item.jsonObject
            add(buildJsonObject {
                put("id", obj.string("id")); put("name", obj.string("name")); put("status", obj.string("status"))
                put("conclusion", obj.string("conclusion")); put("head_sha", obj.string("head_sha")); put("html_url", obj.string("html_url"))
            })
        } } }.toString()
    }

    private suspend fun dispatch(repo: String, workflow: String, ref: String): String {
        val response = client.request("POST", "/repos/$repo/actions/workflows/$workflow/dispatches", buildJsonObject { put("ref", ref) })
        if (response.code !in 200..299) error("GitHub returned HTTP ${response.code}: ${response.body.take(300)}")
        return buildJsonObject { put("ok", true); put("workflow", workflow); put("ref", ref) }.toString()
    }

    private fun JsonObject.string(key: String, default: String = ""): String = this[key]?.jsonPrimitive?.content ?: default
    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name = name, description = description, parameters = ToolParameters(properties = properties, required = required)))
    private fun errorJson(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()
    override fun handles(name: String): Boolean = name in names
}
