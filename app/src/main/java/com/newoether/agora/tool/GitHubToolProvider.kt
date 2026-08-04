package com.newoether.agora.tool

import android.content.Context
import android.util.Base64
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.viewmodel.GenerationContext
import java.net.URLEncoder
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

/** Bounded GitHub REST tools. Credentials stay inside [GitHubApiClient]. */
class GitHubToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    /** Remote mutations are fail-closed unless the user approves them. */
    var confirm: (suspend (repository: String, summary: String) -> Boolean)? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val names = setOf(
        "github_list_repositories", "github_create_repository", "github_read_file", "github_create_branch",
        "github_write_file", "github_get_workflow_runs", "github_dispatch_workflow",
        "github_list_branches", "github_list_commits", "github_get_tree",
        "github_search_code", "github_compare_refs", "github_get_pull_request",
        "github_get_workflow_run_details", "github_list_workflow_artifacts",
        "github_list_user_repositories", "github_search_repositories", "github_get_repository",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            tool("github_list_repositories", "List up to 100 repositories accessible to the signed-in GitHub account.", emptyMap()),
            tool("github_list_user_repositories", "List public repositories belonging to any GitHub user or organization.", mapOf(
                "owner" to string("GitHub user or organization login."),
                "limit" to integer("Maximum repositories, 1-50. Defaults to 30."),
            ), listOf("owner")),
            tool("github_search_repositories", "Search public GitHub repositories by name, owner, topic, language, or GitHub search qualifiers.", mapOf(
                "query" to string("GitHub repository search query, for example 'agora language:kotlin'."),
                "limit" to integer("Maximum results, 1-20. Defaults to 10."),
            ), listOf("query")),
            tool("github_get_repository", "Get metadata and the default branch for any public repository, or an accessible private repository.", mapOf(
                "repo" to string("Repository in owner/name form."),
            ), listOf("repo")),
            tool("github_create_repository", "Create a repository after explicit user confirmation.", mapOf(
                "name" to string("Repository name, 1-100 safe characters."),
                "description" to string("Optional description."),
                "private" to ToolProperty("boolean", "Defaults to true."),
                "auto_init" to ToolProperty("boolean", "Initialize main with README; defaults to true."),
            ), listOf("name")),
            tool("github_read_file", "Read a bounded UTF-8 preview or list one repository directory.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "path" to string("Repository-relative path; empty means root."),
                "ref" to string("Branch, tag, or commit; defaults to main."),
            ), listOf("repo", "path")),
            tool("github_list_branches", "List repository branches without file contents.", mapOf(
                "repo" to string("Repository in owner/name form."), "limit" to integer("Maximum 1-100.")), listOf("repo")),
            tool("github_list_commits", "List bounded commit metadata for a ref or path.", mapOf(
                "repo" to string("Repository in owner/name form."), "ref" to string("Branch/tag/SHA; defaults to main."),
                "path" to string("Optional repository-relative path filter."), "limit" to integer("Maximum 1-50.")), listOf("repo")),
            tool("github_get_tree", "List a bounded Git tree for one explicit ref. Recursive mode is capped and may be truncated.", mapOf(
                "repo" to string("Repository in owner/name form."), "ref" to string("Branch/tag/SHA; defaults to main."),
                "recursive" to ToolProperty("boolean", "Whether to request a recursive tree."), "limit" to integer("Maximum entries 1-500.")), listOf("repo")),
            tool("github_search_code", "Search code in one repository and return bounded path metadata and text matches, never full files.", mapOf(
                "repo" to string("Repository in owner/name form."), "query" to string("Specific code search query."),
                "limit" to integer("Maximum matches 1-30.")), listOf("repo", "query")),
            tool("github_compare_refs", "Compare two refs and return bounded commit/file diff metadata without patches.", mapOf(
                "repo" to string("Repository in owner/name form."), "base" to string("Base ref."),
                "head" to string("Head ref."), "limit" to integer("Maximum changed files 1-100.")), listOf("repo", "base", "head")),
            tool("github_get_pull_request", "Read one pull request and bounded changed-file/check metadata.", mapOf(
                "repo" to string("Repository in owner/name form."), "number" to integer("Pull request number."),
                "file_limit" to integer("Maximum changed files 1-100.")), listOf("repo", "number")),
            tool("github_get_workflow_runs", "Get recent workflow runs.", mapOf(
                "repo" to string("Repository in owner/name form."), "limit" to integer("Maximum 1-20.")), listOf("repo")),
            tool("github_get_workflow_run_details", "Get one workflow run, jobs, and failed-step metadata; does not download raw logs.", mapOf(
                "repo" to string("Repository in owner/name form."), "run_id" to integer("Actions run ID.")), listOf("repo", "run_id")),
            tool("github_list_workflow_artifacts", "List artifact metadata for one workflow run; does not download artifact bodies.", mapOf(
                "repo" to string("Repository in owner/name form."), "run_id" to integer("Actions run ID."),
                "limit" to integer("Maximum 1-100.")), listOf("repo", "run_id")),
            tool("github_create_branch", "Create a workbench/* branch. Direct main/master mutations are rejected.", mapOf(
                "repo" to string("Repository in owner/name form."), "branch" to string("New workbench/* branch."),
                "base" to string("Base branch; defaults to main.")), listOf("repo", "branch")),
            tool("github_write_file", "Create/update one UTF-8 file on an existing workbench/* branch.", mapOf(
                "repo" to string("Repository in owner/name form."), "path" to string("Repository-relative file path."),
                "branch" to string("Target workbench/* branch."), "message" to string("Commit message."),
                "content" to string("Complete UTF-8 file content.")), listOf("repo", "path", "branch", "message", "content")),
            tool("github_dispatch_workflow", "Trigger workflow_dispatch on an explicit ref. This is a remote mutation.", mapOf(
                "repo" to string("Repository in owner/name form."), "workflow" to string("Workflow file name or ID."),
                "ref" to string("Git ref; defaults to main.")), listOf("repo", "workflow")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in. Open Settings → GitHub Workbench.")
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return errorJson("Invalid tool arguments") }
        fun arg(key: String, default: String = "") = (args[key] as? JsonPrimitive)?.content ?: default
        fun intArg(key: String, default: Int) = arg(key).toIntOrNull() ?: default
        fun boolArg(key: String, default: Boolean) = arg(key).toBooleanStrictOrNull() ?: default
        return runCatching {
            when (name) {
                "github_list_repositories" -> listRepositories()
                "github_list_user_repositories" -> listUserRepositories(arg("owner"), intArg("limit", 30))
                "github_search_repositories" -> searchRepositories(arg("query"), intArg("limit", 10))
                "github_get_repository" -> getRepository(arg("repo"))
                "github_create_repository" -> {
                    if (!confirmMutation(arg("name"), "Create GitHub repository ${arg("name")}")) deniedJson()
                    createRepository(arg("name"), arg("description"), boolArg("private", true), boolArg("auto_init", true))
                }
                "github_read_file" -> readFileOrDirectory(arg("repo"), arg("path"), arg("ref", "main"))
                "github_list_branches" -> listBranches(arg("repo"), intArg("limit", 30))
                "github_list_commits" -> listCommits(arg("repo"), arg("ref", "main"), arg("path"), intArg("limit", 20))
                "github_get_tree" -> getTree(arg("repo"), arg("ref", "main"), boolArg("recursive", false), intArg("limit", 200))
                "github_search_code" -> searchCode(arg("repo"), arg("query"), intArg("limit", 20))
                "github_compare_refs" -> compareRefs(arg("repo"), arg("base"), arg("head"), intArg("limit", 50))
                "github_get_pull_request" -> getPullRequest(arg("repo"), intArg("number", 0), intArg("file_limit", 50))
                "github_get_workflow_runs" -> workflowRuns(arg("repo"), intArg("limit", 10))
                "github_get_workflow_run_details" -> workflowRunDetails(arg("repo"), arg("run_id"))
                "github_list_workflow_artifacts" -> workflowArtifacts(arg("repo"), arg("run_id"), intArg("limit", 50))
                "github_create_branch" -> {
                    requireWorkbenchBranch(arg("branch"))
                    if (!confirmMutation(arg("repo"), "Create GitHub branch ${arg("repo")}:${arg("branch")}")) deniedJson()
                    buildJsonObject { put("branch", client.createBranch(arg("repo"), arg("branch"), arg("base", "main"))); put("ok", true) }.toString()
                }
                "github_write_file" -> {
                    requireWorkbenchBranch(arg("branch"))
                    if (!confirmMutation(arg("repo"), "Commit ${arg("repo")}:${arg("branch")}/${arg("path")}")) deniedJson()
                    buildJsonObject { put("commit_sha", client.writeFile(arg("repo"), arg("path"), arg("branch"), arg("message"), arg("content"))); put("ok", true) }.toString()
                }
                "github_dispatch_workflow" -> {
                    if (!confirmMutation(arg("repo"), "Dispatch ${arg("repo")} workflow ${arg("workflow")} on ${arg("ref", "main")}")) deniedJson()
                    dispatch(arg("repo"), arg("workflow"), arg("ref", "main"))
                }
                else -> errorJson("Unknown GitHub tool")
            }
        }.getOrElse { errorJson(it.message ?: "GitHub operation failed") }
    }

    private suspend fun confirmMutation(repository: String, summary: String): Boolean =
         confirm?.invoke(repository, summary) ?: false

    private suspend fun createRepository(name: String, description: String, privateRepo: Boolean, autoInit: Boolean): String {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid repository name" }
        val response = client.request("POST", "/user/repos", buildJsonObject {
            put("name", name)
            if (description.isNotBlank()) put("description", description.take(350))
            put("private", privateRepo)
            put("auto_init", autoInit)
        })
        requireSuccess(response.code, response.body)
        val obj = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("ok", true)
            put("full_name", obj.str("full_name"))
            put("private", obj.bool("private", privateRepo))
            put("default_branch", obj.str("default_branch", "main"))
            put("html_url", obj.str("html_url"))
        }.toString()
    }

    private suspend fun listRepositories(): String {
        val array = getArray("/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100")
        return buildJsonObject { putJsonArray("repositories") { array.forEach { item ->
            val o = item.jsonObject
            add(buildJsonObject { put("full_name", o.str("full_name")); put("private", o.bool("private")); put("default_branch", o.str("default_branch", "main")); put("updated_at", o.str("updated_at")) })
        } } }.toString()
    }

    private suspend fun listUserRepositories(owner: String, limit: Int): String {
        val safeLimit = limit.coerceIn(1, 50)
        val array = client.publicRequest("GET", "/users/${client.encodeSegment(owner)}/repos?sort=updated&per_page=$safeLimit").let { resp ->
            if (resp.code !in 200..299) throw IllegalStateException("GitHub HTTP ${resp.code}: ${resp.body.take(300)}")
            json.parseToJsonElement(resp.body).jsonArray
        }
        return buildJsonObject { put("owner", owner); putJsonArray("repositories") { array.forEach { item ->
            val o = item.jsonObject
            add(buildJsonObject { put("full_name", o.str("full_name")); put("description", o.str("description")); put("default_branch", o.str("default_branch", "main")); put("language", o.str("language")); put("stars", o.long("stargazers_count")); put("updated_at", o.str("updated_at")) })
        } } }.toString()
    }

    private suspend fun searchRepositories(query: String, limit: Int): String {
        val safeLimit = limit.coerceIn(1, 20)
        val resp = client.publicRequest("GET", "/search/repositories?q=${client.encodeSegment(query)}&per_page=$safeLimit")
        if (resp.code !in 200..299) throw IllegalStateException("GitHub HTTP ${resp.code}: ${resp.body.take(300)}")
        val obj = json.parseToJsonElement(resp.body).jsonObject
        val array = obj["items"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { put("total_count", obj.long("total_count")); putJsonArray("repositories") { array.forEach { item ->
            val o = item.jsonObject
            add(buildJsonObject { put("full_name", o.str("full_name")); put("description", o.str("description")); put("default_branch", o.str("default_branch", "main")); put("language", o.str("language")); put("stars", o.long("stargazers_count")); put("url", o.str("html_url")) })
        } } }.toString()
    }

    private suspend fun getRepository(repo: String): String {
        val safeRepo = client.validateRepo(repo)
        val o = client.repository(safeRepo)
        return buildJsonObject {
            put("full_name", o.str("full_name")); put("description", o.str("description"))
            put("private", o.bool("private")); put("default_branch", o.str("default_branch", "main"))
            put("language", o.str("language")); put("stars", o.long("stargazers_count"))
            put("forks", o.long("forks_count")); put("open_issues", o.long("open_issues_count"))
            put("created_at", o.str("created_at")); put("updated_at", o.str("updated_at"))
            put("url", o.str("html_url"))
        }.toString()
    }

    private suspend fun readFileOrDirectory(repo: String, path: String, ref: String): String {
        requireRepo(repo)
        val payload = client.readContent(repo, path, ref)
        return when (payload) {
            is JsonArray -> buildJsonObject {
                put("repo", repo); put("path", path); put("ref", ref); put("type", "dir")
                putJsonArray("entries") { payload.take(MAX_DIRECTORY_ENTRIES).forEach { e ->
                    val o = e.jsonObject
                    add(buildJsonObject { put("name", o.str("name")); put("path", o.str("path")); put("type", o.str("type")); put("sha", o.str("sha")); put("size", o.long("size")) })
                } }
                put("truncated", payload.size > MAX_DIRECTORY_ENTRIES)
            }.toString()
            is JsonObject -> {
                require(payload.str("type") == "file") { "Unsupported content type: ${payload.str("type", "unknown")}" }
                val bytes = Base64.decode(payload["content"]?.jsonPrimitive?.content?.replace("\n", "").orEmpty(), Base64.DEFAULT)
                val text = bytes.toString(Charsets.UTF_8)
                buildJsonObject {
                    put("repo", repo); put("path", path); put("ref", ref); put("type", "file"); put("sha", payload.str("sha")); put("size", payload.long("size", bytes.size.toLong()))
                    put("content", text.take(MAX_FILE_PREVIEW_CHARS) + if (text.length > MAX_FILE_PREVIEW_CHARS) "\n…[preview truncated]" else "")
                    put("truncated", text.length > MAX_FILE_PREVIEW_CHARS)
                }.toString()
            }
            else -> error("Unexpected GitHub contents response")
        }
    }

    private suspend fun listBranches(repo: String, requested: Int): String {
        val limit = requested.coerceIn(1, 100)
        val a = getArray("/repos/${validRepo(repo)}/branches?per_page=$limit")
        return buildJsonObject { putJsonArray("branches") { a.take(limit).forEach { e -> val o=e.jsonObject; add(buildJsonObject { put("name",o.str("name")); put("sha",o["commit"]?.jsonObject?.str("sha").orEmpty()); put("protected",o.bool("protected")) }) } } }.toString()
    }

    private suspend fun listCommits(repo: String, ref: String, path: String, requested: Int): String {
        val limit = requested.coerceIn(1, 50)
        val suffix = if (path.isBlank()) "" else "&path=${enc(path)}"
        val a = getArray("/repos/${validRepo(repo)}/commits?sha=${enc(ref)}&per_page=$limit$suffix")
        return buildJsonObject { putJsonArray("commits") { a.take(limit).forEach { e -> val o=e.jsonObject; val c=o["commit"]?.jsonObject; add(buildJsonObject { put("sha",o.str("sha")); put("message",c?.str("message")?.take(500).orEmpty()); put("author",c?.get("author")?.jsonObject?.str("name").orEmpty()); put("date",c?.get("author")?.jsonObject?.str("date").orEmpty()); put("html_url",o.str("html_url")) }) } } }.toString()
    }

    private suspend fun getTree(repo: String, ref: String, recursive: Boolean, requested: Int): String {
        val limit = requested.coerceIn(1, 500)
        val response = getObject("/repos/${validRepo(repo)}/git/trees/${enc(ref)}${if (recursive) "?recursive=1" else ""}")
        val tree = response["tree"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { put("sha",response.str("sha")); put("api_truncated",response.bool("truncated")); put("truncated",response.bool("truncated") || tree.size > limit); putJsonArray("entries") { tree.take(limit).forEach { e -> val o=e.jsonObject; add(buildJsonObject { put("path",o.str("path")); put("type",o.str("type")); put("mode",o.str("mode")); put("sha",o.str("sha")); put("size",o.long("size")) }) } } }.toString()
    }

    private suspend fun searchCode(repo: String, query: String, requested: Int): String {
        require(query.trim().length in 2..200) { "Search query must be 2-200 characters" }
        val limit=requested.coerceIn(1,30)
        val o=getObject("/search/code?q=${enc(query.trim()+" repo:"+validRepo(repo))}&per_page=$limit")
        val items=o["items"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { put("total_count",o.long("total_count")); put("incomplete_results",o.bool("incomplete_results")); putJsonArray("items") { items.take(limit).forEach { e -> val i=e.jsonObject; add(buildJsonObject { put("name",i.str("name")); put("path",i.str("path")); put("sha",i.str("sha")); put("html_url",i.str("html_url")) }) } } }.toString()
    }

    private suspend fun compareRefs(repo: String, base: String, head: String, requested: Int): String {
        val limit=requested.coerceIn(1,100)
        val o=getObject("/repos/${validRepo(repo)}/compare/${enc(base)}...${enc(head)}")
        val files=o["files"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { put("status",o.str("status")); put("ahead_by",o.long("ahead_by")); put("behind_by",o.long("behind_by")); put("total_commits",o.long("total_commits")); put("files_truncated",files.size>limit); putJsonArray("files") { files.take(limit).forEach { e -> val f=e.jsonObject; add(buildJsonObject { put("filename",f.str("filename")); put("status",f.str("status")); put("additions",f.long("additions")); put("deletions",f.long("deletions")); put("changes",f.long("changes")); put("previous_filename",f.str("previous_filename")) }) } } }.toString()
    }

    private suspend fun getPullRequest(repo: String, number: Int, requested: Int): String {
        require(number > 0) { "Pull request number must be positive" }
        val r=validRepo(repo); val p=getObject("/repos/$r/pulls/$number"); val limit=requested.coerceIn(1,100)
        val files=getArray("/repos/$r/pulls/$number/files?per_page=$limit")
        val sha=p["head"]?.jsonObject?.str("sha").orEmpty()
        val checks=if(sha.isBlank()) null else getObject("/repos/$r/commits/${enc(sha)}/check-runs?per_page=50")
        return buildJsonObject { put("number",number); put("state",p.str("state")); put("title",p.str("title")); put("draft",p.bool("draft")); put("mergeable",p.str("mergeable")); put("mergeable_state",p.str("mergeable_state")); put("base",p["base"]?.jsonObject?.str("ref").orEmpty()); put("head",p["head"]?.jsonObject?.str("ref").orEmpty()); put("head_sha",sha); put("html_url",p.str("html_url")); putJsonArray("files") { files.take(limit).forEach { e -> val f=e.jsonObject; add(buildJsonObject { put("filename",f.str("filename")); put("status",f.str("status")); put("changes",f.long("changes")) }) }; }; putJsonArray("checks") { checks?.get("check_runs")?.jsonArray?.take(50)?.forEach { e -> val c=e.jsonObject; add(buildJsonObject { put("name",c.str("name")); put("status",c.str("status")); put("conclusion",c.str("conclusion")); put("html_url",c.str("html_url")) }) } } }.toString()
    }

    private suspend fun workflowRuns(repo: String, requested: Int): String {
        val limit=requested.coerceIn(1,20); val o=getObject("/repos/${validRepo(repo)}/actions/runs?per_page=$limit"); val runs=o["workflow_runs"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { putJsonArray("runs") { runs.take(limit).forEach { e -> val r=e.jsonObject; add(runSummary(r)) } } }.toString()
    }

    private suspend fun workflowRunDetails(repo: String, runId: String): String {
        require(runId.toLongOrNull()?.let { it>0 } == true) { "run_id must be positive" }; val r=validRepo(repo)
        val run=getObject("/repos/$r/actions/runs/$runId"); val jobs=getObject("/repos/$r/actions/runs/$runId/jobs?per_page=100")["jobs"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { put("run",runSummary(run)); putJsonArray("jobs") { jobs.take(100).forEach { e -> val j=e.jsonObject; add(buildJsonObject { put("id",j.long("id")); put("name",j.str("name")); put("status",j.str("status")); put("conclusion",j.str("conclusion")); put("html_url",j.str("html_url")); putJsonArray("failed_steps") { j["steps"]?.jsonArray?.filter { it.jsonObject.str("conclusion") in setOf("failure","cancelled","timed_out") }?.take(30)?.forEach { s -> val x=s.jsonObject; add(buildJsonObject { put("number",x.long("number")); put("name",x.str("name")); put("conclusion",x.str("conclusion")) }) } } }) } } }.toString()
    }

    private suspend fun workflowArtifacts(repo: String, runId: String, requested: Int): String {
        require(runId.toLongOrNull()?.let { it>0 } == true) { "run_id must be positive" }; val limit=requested.coerceIn(1,100)
        val o=getObject("/repos/${validRepo(repo)}/actions/runs/$runId/artifacts?per_page=$limit"); val a=o["artifacts"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { put("total_count",o.long("total_count")); putJsonArray("artifacts") { a.take(limit).forEach { e -> val x=e.jsonObject; add(buildJsonObject { put("id",x.long("id")); put("name",x.str("name")); put("size_in_bytes",x.long("size_in_bytes")); put("expired",x.bool("expired")); put("created_at",x.str("created_at")); put("expires_at",x.str("expires_at")) }) } } }.toString()
    }

    private suspend fun dispatch(repo:String, workflow:String, ref:String):String { require(workflow.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "Invalid workflow" }; val x=client.request("POST","/repos/${validRepo(repo)}/actions/workflows/$workflow/dispatches",buildJsonObject { put("ref",ref) }); requireSuccess(x.code,x.body); return buildJsonObject { put("ok",true); put("workflow",workflow); put("ref",ref) }.toString() }
    private suspend fun getObject(path:String):JsonObject { val r=client.request("GET",path); requireSuccess(r.code,r.body); return json.parseToJsonElement(r.body).jsonObject }
    private suspend fun getArray(path:String):JsonArray { val r=client.request("GET",path); requireSuccess(r.code,r.body); return json.parseToJsonElement(r.body).jsonArray }
    private fun requireSuccess(code:Int, body:String) { if(code !in 200..299) { val msg=runCatching { json.parseToJsonElement(body).jsonObject.str("message") }.getOrDefault("GitHub API error"); error("$msg (HTTP $code)") } }
    private fun runSummary(o:JsonObject)=buildJsonObject { put("id",o.long("id")); put("name",o.str("name")); put("event",o.str("event")); put("status",o.str("status")); put("conclusion",o.str("conclusion")); put("head_branch",o.str("head_branch")); put("head_sha",o.str("head_sha")); put("run_attempt",o.long("run_attempt")); put("html_url",o.str("html_url")); put("created_at",o.str("created_at")); put("updated_at",o.str("updated_at")) }
    private fun requireWorkbenchBranch(branch:String) { require(branch.startsWith("workbench/") && branch.length in 11..200) { "Writes require a workbench/* branch" } }
    private fun requireRepo(repo:String) { require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))) { "Invalid owner/name repository" } }
    private fun validRepo(repo:String):String { requireRepo(repo); return repo }
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8").replace("+","%20")
    private fun JsonObject.str(k:String,d:String="")=this[k]?.jsonPrimitive?.content ?: d
    private fun JsonObject.long(k:String,d:Long=0)=this[k]?.jsonPrimitive?.content?.toLongOrNull() ?: d
    private fun JsonObject.bool(k:String,d:Boolean=false)=this[k]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: d
    private fun tool(name:String,description:String,properties:Map<String,ToolProperty>,required:List<String> = emptyList())=ToolDefinition(function=ToolFunction(name=name,description=description,parameters=ToolParameters(properties=properties,required=required)))
    private fun deniedJson() = errorJson("GitHub action denied")
    private fun errorJson(message:String)=buildJsonObject { put("ok",false); put("error",message.take(500)) }.toString()
    override fun handles(name:String)=name in names

    private companion object { const val MAX_FILE_PREVIEW_CHARS=100_000; const val MAX_DIRECTORY_ENTRIES=500 }
}
