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

/** GitHub tools: public repositories are read-only without login; mutations always require login. */
class GitHubToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    /** Reuses the app confirmation dialog for every repository mutation. */
    var confirm: (suspend (summary: String) -> Boolean)? = null

    private val names = setOf(
        "github_list_repositories", "github_list_user_repositories", "github_search_repositories",
        "github_get_repository", "github_read_file", "github_create_branch", "github_write_file",
        "github_get_workflow_runs", "github_dispatch_workflow",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            tool("github_list_repositories", "List repositories accessible to the signed-in GitHub account, including private repositories.", emptyMap()),
            tool("github_list_user_repositories", "List public repositories belonging to any GitHub user or organization.", mapOf(
                "owner" to string("GitHub user or organization login."),
                "limit" to integer("Maximum repositories, 1-50. Defaults to 30."),
            ), listOf("owner")),
            tool("github_search_repositories", "Search public GitHub repositories by name, owner, topic, language, or GitHub search qualifiers.", mapOf(
                "query" to string("GitHub repository search query, for example 'agora language:kotlin'."),
                "limit" to integer("Maximum results, 1-20. Defaults to 10."),
            ), listOf("query")),
            tool("github_get_repository", "Get metadata and the default branch for any public repository, or an accessible private repository.", mapOf(
                "repo" to string("Repository in owner/name form or a github.com repository URL."),
            ), listOf("repo")),
            tool("github_read_file", "Read a UTF-8 text file or list a directory. Public repositories need no login; private repositories use the signed-in account.", mapOf(
                "repo" to string("Repository in owner/name form or a github.com repository URL."),
                "path" to string("Repository-relative file or directory path. Use an empty string for the root."),
                "ref" to string("Branch, tag, or commit. Empty means the repository default branch."),
            ), listOf("repo", "path")),
            tool("github_create_branch", "Create a branch in a repository writable by the signed-in account.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "branch" to string("New branch name. Prefer workbench/<task>."),
                "base" to string("Base branch. Defaults to main."),
            ), listOf("repo", "branch")),
            tool("github_write_file", "Create or update one UTF-8 text file and commit it to an existing branch. Requires login and confirmation.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "path" to string("Repository-relative file path."),
                "branch" to string("Target branch."),
                "message" to string("Commit message."),
                "content" to string("Complete new UTF-8 file content."),
            ), listOf("repo", "path", "branch", "message", "content")),
            tool("github_get_workflow_runs", "Get recent Actions runs for a public or accessible private repository.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "limit" to integer("Maximum runs, 1-20."),
            ), listOf("repo")),
            tool("github_dispatch_workflow", "Trigger workflow_dispatch. Requires login, repository permission, and confirmation.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "workflow" to string("Workflow file name or numeric ID."),
                "ref" to string("Git ref. Defaults to main."),
            ), listOf("repo", "workflow")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return errorJson("Invalid tool arguments") }
        fun arg(key: String, default: String = "") = (args[key] as? JsonPrimitive)?.content ?: default
        fun intArg(key: String, default: Int) = arg(key).toIntOrNull() ?: default

        return runCatching {
            when (name) {
                "github_list_repositories" -> {
                    requireSignedIn()
                    listRepositories()
                }
                "github_list_user_repositories" -> listUserRepositories(arg("owner"), intArg("limit", 30))
                "github_search_repositories" -> searchRepositories(arg("query"), intArg("limit", 10))
                "github_get_repository" -> repositoryMetadata(arg("repo"))
                "github_read_file" -> readFileOrDirectory(arg("repo"), arg("path"), arg("ref"))
                "github_create_branch" -> {
                    requireSignedIn()
                    val repo = client.validateRepo(arg("repo"))
                    checkConfirmed("Create branch ${arg("branch")} in $repo")
                    buildJsonObject {
                        put("branch", client.createBranch(repo, arg("branch"), arg("base", "main")))
                        put("ok", true)
                    }.toString()
                }
                "github_write_file" -> {
                    requireSignedIn()
                    val repo = client.validateRepo(arg("repo"))
                    checkConfirmed("Commit $repo:${arg("branch")}/${arg("path")}")
                    buildJsonObject {
                        put("commit_sha", client.writeFile(repo, arg("path"), arg("branch"), arg("message"), arg("content")))
                        put("ok", true)
                    }.toString()
                }
                "github_get_workflow_runs" -> workflowRuns(arg("repo"), intArg("limit", 10))
                "github_dispatch_workflow" -> {
                    requireSignedIn()
                    val repo = client.validateRepo(arg("repo"))
                    checkConfirmed("Dispatch workflow ${arg("workflow")} in $repo at ${arg("ref", "main")}")
                    dispatch(repo, arg("workflow"), arg("ref", "main"))
                }
                else -> errorJson("Unknown GitHub tool")
            }
        }.getOrElse { errorJson(it.message ?: "GitHub operation failed") }
    }

    private fun requireSignedIn() {
        check(client.isSignedIn()) {
            "GitHub is not signed in. Public repository browsing still works; sign in under Settings → GitHub Workbench for private repositories or changes."
        }
    }

    private suspend fun checkConfirmed(summary: String) {
        if (confirm?.invoke(summary) != true) error("Denied by user")
    }

    private suspend fun listRepositories(): String {
        val response = client.request("GET", "/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=50")
        requireSuccess(response)
        return repositoriesJson(json.parseToJsonElement(response.body).jsonArray)
    }

    private suspend fun listUserRepositories(owner: String, requested: Int): String {
        require(owner.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid GitHub owner" }
        val limit = requested.coerceIn(1, 50)
        // /users also handles organization-owned public repositories and avoids requiring auth.
        val response = client.publicRequest("GET", "/users/$owner/repos?type=public&sort=updated&per_page=$limit")
        requireSuccess(response)
        return repositoriesJson(json.parseToJsonElement(response.body).jsonArray)
    }

    private suspend fun searchRepositories(query: String, requested: Int): String {
        val clean = query.trim()
        require(clean.isNotEmpty() && clean.length <= 256) { "Search query must contain 1-256 characters" }
        val limit = requested.coerceIn(1, 20)
        val encoded = java.net.URLEncoder.encode(clean, "UTF-8").replace("+", "%20")
        val response = client.publicRequest("GET", "/search/repositories?q=$encoded&sort=updated&per_page=$limit")
        requireSuccess(response)
        val root = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("total_count", root["total_count"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
            put("results", json.parseToJsonElement(repositoriesJson(root["items"]?.jsonArray ?: JsonArray(emptyList()))).jsonObject["repositories"]!!)
        }.toString()
    }

    private suspend fun repositoryMetadata(repo: String): String {
        val obj = client.repository(repo)
        return buildJsonObject {
            put("full_name", obj.string("full_name"))
            put("private", obj.boolean("private"))
            put("description", obj.string("description"))
            put("default_branch", obj.string("default_branch", "main"))
            put("language", obj.string("language"))
            put("stars", obj.long("stargazers_count"))
            put("forks", obj.long("forks_count"))
            put("archived", obj.boolean("archived"))
            put("html_url", obj.string("html_url"))
            put("updated_at", obj.string("updated_at"))
        }.toString()
    }

    private fun repositoriesJson(repos: JsonArray): String = buildJsonObject {
        putJsonArray("repositories") {
            repos.forEach { item ->
                val obj = item as? JsonObject ?: return@forEach
                add(buildJsonObject {
                    put("full_name", obj.string("full_name"))
                    put("private", obj.boolean("private"))
                    put("default_branch", obj.string("default_branch", "main"))
                    put("description", obj.string("description"))
                    put("language", obj.string("language"))
                    put("stars", obj.long("stargazers_count"))
                    put("updated_at", obj.string("updated_at"))
                })
            }
        }
    }.toString()

    private suspend fun readFileOrDirectory(repo: String, path: String, ref: String): String {
        val safeRepo = client.validateRepo(repo)
        val payload = client.readContent(safeRepo, path, ref)
        return when (payload) {
            is JsonArray -> buildJsonObject {
                put("repo", safeRepo); put("path", path); put("ref", ref); put("type", "dir")
                putJsonArray("entries") {
                    payload.take(MAX_DIRECTORY_ENTRIES).forEach { element ->
                        val item = element as? JsonObject ?: return@forEach
                        add(buildJsonObject {
                            put("name", item.string("name")); put("path", item.string("path")); put("type", item.string("type"))
                            put("sha", item.string("sha")); put("size", item.long("size"))
                        })
                    }
                }
                put("truncated", payload.size > MAX_DIRECTORY_ENTRIES)
            }.toString()
            is JsonObject -> {
                if (payload.string("type") != "file") error("Unsupported GitHub content type: ${payload.string("type", "unknown")}")
                val reportedSize = payload.long("size")
                require(reportedSize <= MAX_DOWNLOAD_BYTES) { "File is too large to preview ($reportedSize bytes; $MAX_DOWNLOAD_BYTES byte limit)" }
                val raw = payload["content"]?.jsonPrimitive?.content?.replace("\n", "") ?: ""
                val bytes = Base64.decode(raw, Base64.DEFAULT)
                val text = bytes.toString(Charsets.UTF_8)
                buildJsonObject {
                    put("repo", safeRepo); put("path", path); put("ref", ref); put("type", "file")
                    put("sha", payload.string("sha")); put("size", reportedSize)
                    put("content", if (text.length <= MAX_FILE_PREVIEW_CHARS) text else text.take(MAX_FILE_PREVIEW_CHARS) + "\n…[GitHub file preview truncated]")
                    put("truncated", text.length > MAX_FILE_PREVIEW_CHARS)
                }.toString()
            }
            else -> error("GitHub Contents API returned neither a file nor a directory")
        }
    }

    private suspend fun workflowRuns(repo: String, requested: Int): String {
        val safeRepo = client.validateRepo(repo)
        val limit = requested.coerceIn(1, 20)
        val response = client.publicRequest("GET", "/repos/$safeRepo/actions/runs?per_page=$limit")
        requireSuccess(response)
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
        require(workflow.matches(Regex("[A-Za-z0-9_.-]{1,200}"))) { "Invalid workflow name or ID" }
        val response = client.request("POST", "/repos/$repo/actions/workflows/$workflow/dispatches", buildJsonObject { put("ref", ref) })
        requireSuccess(response)
        return buildJsonObject { put("ok", true); put("workflow", workflow); put("ref", ref) }.toString()
    }

    private fun requireSuccess(response: com.newoether.agora.github.GitHubApiResponse) {
        if (response.code !in 200..299) {
            val message = runCatching { json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                ?: "GitHub API error"
            error("$message (HTTP ${response.code})")
        }
    }

    private fun JsonObject.string(key: String, default: String = ""): String =
        this[key]?.jsonPrimitive?.content?.takeUnless { it == "null" } ?: default
    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    private fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name = name, description = description, parameters = ToolParameters(properties = properties, required = required)))
    private fun errorJson(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()
    override fun handles(name: String): Boolean = name in names

    private companion object {
        const val MAX_DIRECTORY_ENTRIES = 500
        const val MAX_DOWNLOAD_BYTES = 1_000_000L
        const val MAX_FILE_PREVIEW_CHARS = 100_000
    }
}
