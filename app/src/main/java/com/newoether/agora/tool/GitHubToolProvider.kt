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

/**
 * Existing GitHub tool surface with two separate access paths:
 * 1. signed-in account repositories (including permitted private repositories);
 * 2. read-only public repositories owned by [PUBLIC_REPOSITORY_OWNERS].
 *
 * No extra search/browse tool is introduced. The existing list/read tools expose both sources.
 */
class GitHubToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    /** Mutations must be explicitly approved by the host UI. */
    var confirm: (suspend (summary: String) -> Boolean)? = null

    private val names = setOf(
        "github_list_repositories", "github_read_file", "github_create_branch",
        "github_write_file", "github_get_workflow_runs", "github_dispatch_workflow",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        return listOf(
            tool(
                "github_list_repositories",
                "List repositories accessible to the signed-in account plus all public repositories from the configured public owners.",
                emptyMap(),
            ),
            tool("github_read_file", "Read a UTF-8 text file or list a directory. Configured public repositories work without login; private/account repositories require login.", mapOf(
                "repo" to string("Repository in owner/name form or a github.com repository URL."),
                "path" to string("Repository-relative file or directory path. Use an empty string for the root directory."),
                "ref" to string("Branch, tag, or commit. Empty means the repository default branch."),
            ), listOf("repo", "path")),
            tool("github_create_branch", "Create a branch in a repository writable by the signed-in account. Requires confirmation.", mapOf(
                "repo" to string("Repository in owner/name form."), "branch" to string("New branch name."),
                "base" to string("Base branch. Defaults to main.")), listOf("repo", "branch")),
            tool("github_write_file", "Create or update one UTF-8 text file and commit it to an existing branch. Requires confirmation.", mapOf(
                "repo" to string("Repository in owner/name form."), "path" to string("Repository-relative file path."),
                "branch" to string("Target branch. Use a workbench branch by default."), "message" to string("Commit message."),
                "content" to string("Complete new UTF-8 file content.")), listOf("repo", "path", "branch", "message", "content")),
            tool("github_get_workflow_runs", "Get recent GitHub Actions workflow runs for an accessible repository.", mapOf(
                "repo" to string("Repository in owner/name form."), "limit" to ToolProperty("integer", "Maximum runs, 1-20.")), listOf("repo")),
            tool("github_dispatch_workflow", "Trigger a workflow_dispatch workflow. Requires login and confirmation.", mapOf(
                "repo" to string("Repository in owner/name form."), "workflow" to string("Workflow file name or numeric ID."),
                "ref" to string("Git ref. Defaults to main.")), listOf("repo", "workflow")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        // Do not gate the provider globally: list/read have an anonymous public path. Account
        // operations and all mutations are checked separately below.
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return errorJson("Invalid tool arguments") }
        fun arg(key: String, default: String = "") = (args[key] as? JsonPrimitive)?.content ?: default

        return runCatching {
            when (name) {
                "github_list_repositories" -> listRepositories()
                "github_read_file" -> readFileOrDirectory(arg("repo"), arg("path"), arg("ref"))
                "github_create_branch" -> {
                    requireSignedIn()
                    checkConfirmed("Create GitHub branch ${arg("repo")}:${arg("branch")}")
                    buildJsonObject {
                        put("branch", client.createBranch(arg("repo"), arg("branch"), arg("base", "main")))
                        put("ok", true)
                    }.toString()
                }
                "github_write_file" -> {
                    requireSignedIn()
                    checkConfirmed("Commit ${arg("repo")}:${arg("branch")}/${arg("path")}")
                    buildJsonObject {
                        put("commit_sha", client.writeFile(arg("repo"), arg("path"), arg("branch"), arg("message"), arg("content")))
                        put("ok", true)
                    }.toString()
                }
                "github_get_workflow_runs" -> {
                    requireSignedIn()
                    workflowRuns(arg("repo"), arg("limit", "10").toIntOrNull() ?: 10)
                }
                "github_dispatch_workflow" -> {
                    requireSignedIn()
                    checkConfirmed("Dispatch ${arg("repo")} workflow ${arg("workflow")}")
                    dispatch(arg("repo"), arg("workflow"), arg("ref", "main"))
                }
                else -> errorJson("Unknown GitHub tool")
            }
        }.getOrElse { errorJson(it.message ?: "GitHub operation failed") }
    }

    private fun requireSignedIn() {
        check(client.isSignedIn()) { "GitHub login is required for this account or mutation operation" }
    }

    private suspend fun checkConfirmed(summary: String) {
        if (confirm?.invoke(summary) != true) error("Denied by user")
    }

    /**
     * Combines the signed-in account list (when available) with every public repository from the
     * five configured owners. Pagination continues until GitHub returns fewer than 100 rows.
     */
    private suspend fun listRepositories(): String {
        val repositories = linkedMapOf<String, JsonObject>()

        if (client.isSignedIn()) {
            var page = 1
            while (page <= MAX_PAGES) {
                val response = client.request(
                    "GET",
                    "/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=$PAGE_SIZE&page=$page",
                )
                requireSuccess(response)
                val rows = json.parseToJsonElement(response.body).jsonArray
                rows.forEach { row ->
                    val obj = row as? JsonObject ?: return@forEach
                    repositories[obj.string("full_name")] = obj
                }
                if (rows.size < PAGE_SIZE) break
                page++
            }
        }

        for (owner in PUBLIC_REPOSITORY_OWNERS) {
            var page = 1
            while (page <= MAX_PAGES) {
                val response = client.request(
                    method = "GET",
                    path = "/users/$owner/repos?type=public&sort=updated&per_page=$PAGE_SIZE&page=$page",
                    requireAuth = false,
                )
                requireSuccess(response)
                val rows = json.parseToJsonElement(response.body).jsonArray
                rows.forEach { row ->
                    val obj = row as? JsonObject ?: return@forEach
                    if (!obj.boolean("private")) repositories[obj.string("full_name")] = obj
                }
                if (rows.size < PAGE_SIZE) break
                page++
            }
        }

        return buildJsonObject {
            put("signed_in", client.isSignedIn())
            putJsonArray("public_owners") { PUBLIC_REPOSITORY_OWNERS.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("repositories") {
                repositories.values
                    .sortedByDescending { it.string("updated_at") }
                    .forEach { obj ->
                        add(buildJsonObject {
                            put("full_name", obj.string("full_name"))
                            put("private", obj.boolean("private"))
                            put("default_branch", obj.string("default_branch", "main"))
                            put("description", obj.string("description"))
                            put("updated_at", obj.string("updated_at"))
                        })
                    }
            }
        }.toString()
    }

    private suspend fun readFileOrDirectory(repo: String, path: String, ref: String): String {
        val normalizedRepo = normalizeRepo(repo)
        val owner = normalizedRepo.substringBefore('/').lowercase()
        if (!client.isSignedIn() && owner !in PUBLIC_REPOSITORY_OWNERS_LOWERCASE) {
            error("Without GitHub login, public reads are limited to: ${PUBLIC_REPOSITORY_OWNERS.joinToString()}")
        }

        val payload = client.readContent(normalizedRepo, path, ref)
        return when (payload) {
            is JsonArray -> buildJsonObject {
                put("repo", normalizedRepo); put("path", path); put("ref", ref); put("type", "dir")
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
                val size = payload.long("size")
                require(size <= MAX_DOWNLOAD_BYTES) { "File is too large to preview ($size bytes)" }
                val raw = payload["content"]?.jsonPrimitive?.content?.replace("\n", "") ?: ""
                val bytes = Base64.decode(raw, Base64.DEFAULT)
                val text = bytes.toString(Charsets.UTF_8)
                buildJsonObject {
                    put("repo", normalizedRepo); put("path", path); put("ref", ref); put("type", "file")
                    put("sha", payload.string("sha")); put("size", size)
                    put("content", if (text.length <= MAX_FILE_PREVIEW_CHARS) text else text.take(MAX_FILE_PREVIEW_CHARS) + "\n…[GitHub file preview truncated]")
                    put("truncated", text.length > MAX_FILE_PREVIEW_CHARS)
                }.toString()
            }
            else -> error("GitHub Contents API returned neither a file nor a directory")
        }
    }

    private suspend fun workflowRuns(repo: String, requested: Int): String {
        val limit = requested.coerceIn(1, 20)
        val response = client.request("GET", "/repos/${normalizeRepo(repo)}/actions/runs?per_page=$limit")
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
        val response = client.request("POST", "/repos/${normalizeRepo(repo)}/actions/workflows/$workflow/dispatches", buildJsonObject { put("ref", ref) })
        requireSuccess(response)
        return buildJsonObject { put("ok", true); put("workflow", workflow); put("ref", ref) }.toString()
    }

    private fun normalizeRepo(value: String): String {
        val repo = value.trim().removePrefix("https://github.com/").removeSuffix("/").removeSuffix(".git")
        require(REPO_PATTERN.matches(repo)) { "Repository must be in owner/name form" }
        return repo
    }

    private fun requireSuccess(response: com.newoether.agora.github.GitHubApiResponse) {
        if (response.code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(response.body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub API error"
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
        val PUBLIC_REPOSITORY_OWNERS = listOf("hzyhhzy", "xulai1001", "HisAtri", "EtherealAO", "Hzyuer")
        val PUBLIC_REPOSITORY_OWNERS_LOWERCASE = PUBLIC_REPOSITORY_OWNERS.mapTo(hashSetOf()) { it.lowercase() }
        val REPO_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 10
        const val MAX_DIRECTORY_ENTRIES = 500
        const val MAX_DOWNLOAD_BYTES = 1_000_000L
        const val MAX_FILE_PREVIEW_CHARS = 100_000
    }
}
