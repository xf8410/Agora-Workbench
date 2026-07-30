package com.newoether.agora.tool

import android.content.Context
import android.util.Base64
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.*

class GitHubToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val names = setOf(
        "github_list_repositories", "github_create_repository", "github_read_file",
        "github_create_branch", "github_write_file", "github_get_workflow_runs",
        "github_get_workflow_run_details", "github_get_workflow_failed_logs",
        "github_dispatch_workflow",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            tool("github_list_repositories", "List repositories accessible to the signed-in GitHub account.", emptyMap()),
            tool("github_create_repository", "Create a repository for the signed-in user. Defaults to private and initializes main with a README.", mapOf(
                "name" to string("Repository name, 1-100 characters; no owner or slash."),
                "description" to string("Optional description."),
                "private" to ToolProperty("boolean", "Private by default."),
                "auto_init" to ToolProperty("boolean", "Initialize README/main; true by default."),
            ), listOf("name")),
            tool("github_read_file", "Read a UTF-8 file or list a repository directory.", mapOf(
                "repo" to string("owner/name"), "path" to string("Repository-relative path."),
                "ref" to string("Branch, tag or commit; defaults to main.")), listOf("repo", "path")),
            tool("github_create_branch", "Create a branch. Prefer workbench/*.", mapOf(
                "repo" to string("owner/name"), "branch" to string("New branch."),
                "base" to string("Base; defaults to main.")), listOf("repo", "branch")),
            tool("github_write_file", "Create/update one UTF-8 file and commit it.", mapOf(
                "repo" to string("owner/name"), "path" to string("File path."),
                "branch" to string("Target branch."), "message" to string("Commit message."),
                "content" to string("Complete content.")), listOf("repo", "path", "branch", "message", "content")),
            tool("github_get_workflow_runs", "Get recent GitHub Actions workflow runs.", mapOf(
                "repo" to string("owner/name"), "limit" to integer("1-20.")), listOf("repo")),
            tool("github_get_workflow_run_details", "Read one Actions run, its jobs, failed steps and artifact metadata without large logs.", mapOf(
                "repo" to string("owner/name"), "run_id" to integer("Workflow run id.")), listOf("repo", "run_id")),
            tool("github_get_workflow_failed_logs", "Read bounded error-focused logs for failed jobs in one Actions run. Never returns an unlimited log.", mapOf(
                "repo" to string("owner/name"), "run_id" to integer("Workflow run id."),
                "max_chars" to integer("Maximum returned log characters, 2000-60000; defaults to 20000.")), listOf("repo", "run_id")),
            tool("github_dispatch_workflow", "Trigger workflow_dispatch.", mapOf(
                "repo" to string("owner/name"), "workflow" to string("Workflow file or id."),
                "ref" to string("Ref; defaults to main.")), listOf("repo", "workflow")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in. Open Settings → GitHub Workbench.")
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return errorJson("Invalid tool arguments") }
        fun arg(key: String, default: String = "") = (args[key] as? JsonPrimitive)?.content ?: default
        fun longArg(key: String) = arg(key).toLongOrNull() ?: error("$key must be an integer")
        fun boolArg(key: String, default: Boolean) = arg(key).toBooleanStrictOrNull() ?: default
        return runCatching {
            when (name) {
                "github_list_repositories" -> listRepositories()
                "github_create_repository" -> createRepository(arg("name"), arg("description"), boolArg("private", true), boolArg("auto_init", true))
                "github_read_file" -> readFileOrDirectory(arg("repo"), arg("path"), arg("ref", "main"))
                "github_create_branch" -> buildJsonObject { put("branch", client.createBranch(arg("repo"), arg("branch"), arg("base", "main"))); put("ok", true) }.toString()
                "github_write_file" -> buildJsonObject { put("commit_sha", client.writeFile(arg("repo"), arg("path"), arg("branch"), arg("message"), arg("content"))); put("ok", true) }.toString()
                "github_get_workflow_runs" -> workflowRuns(arg("repo"), arg("limit", "10").toIntOrNull() ?: 10)
                "github_get_workflow_run_details" -> runDetails(arg("repo"), longArg("run_id"))
                "github_get_workflow_failed_logs" -> failedLogs(arg("repo"), longArg("run_id"), (arg("max_chars", "20000").toIntOrNull() ?: 20000).coerceIn(2000, 60000))
                "github_dispatch_workflow" -> dispatch(arg("repo"), arg("workflow"), arg("ref", "main"))
                else -> errorJson("Unknown GitHub tool")
            }
        }.getOrElse { errorJson(it.message ?: "GitHub operation failed") }
    }

    private suspend fun createRepository(name: String, description: String, privateRepo: Boolean, autoInit: Boolean): String {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid repository name" }
        val response = client.request("POST", "/user/repos", buildJsonObject {
            put("name", name); if (description.isNotBlank()) put("description", description.take(350))
            put("private", privateRepo); put("auto_init", autoInit)
        })
        requireOk(response.code, response.body)
        val obj = json.parseToJsonElement(response.body).jsonObject
        return buildJsonObject {
            put("ok", true); put("full_name", obj.string("full_name")); put("private", obj.bool("private", privateRepo))
            put("default_branch", obj.string("default_branch", "main")); put("html_url", obj.string("html_url"))
        }.toString()
    }

    private suspend fun listRepositories(): String {
        val r = client.request("GET", "/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=50")
        requireOk(r.code, r.body)
        return buildJsonObject { putJsonArray("repositories") { json.parseToJsonElement(r.body).jsonArray.forEach { item ->
            val o = item.jsonObject; add(buildJsonObject { put("full_name", o.string("full_name")); put("private", o.bool("private")); put("default_branch", o.string("default_branch", "main")) })
        } } }.toString()
    }

    private suspend fun readFileOrDirectory(repo: String, path: String, ref: String): String = when (val payload = client.readContent(repo, path, ref)) {
        is JsonArray -> buildJsonObject { put("type", "dir"); putJsonArray("entries") { payload.forEach { e -> val o=e.jsonObject; add(buildJsonObject { put("name",o.string("name")); put("path",o.string("path")); put("type",o.string("type")); put("sha",o.string("sha")) }) } } }.toString()
        is JsonObject -> {
            require(payload.string("type") == "file") { "Unsupported content type" }
            val bytes = Base64.decode(payload["content"]?.jsonPrimitive?.content?.replace("\n", "").orEmpty(), Base64.DEFAULT)
            val text = bytes.toString(Charsets.UTF_8); val max = 100_000
            buildJsonObject { put("type","file"); put("sha",payload.string("sha")); put("size",bytes.size); put("content",text.take(max)); put("truncated",text.length>max) }.toString()
        }
        else -> error("Unexpected GitHub content response")
    }

    private suspend fun workflowRuns(repo: String, requested: Int): String {
        val r = client.request("GET", "/repos/$repo/actions/runs?per_page=${requested.coerceIn(1,20)}"); requireOk(r.code,r.body)
        val runs=json.parseToJsonElement(r.body).jsonObject["workflow_runs"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject { putJsonArray("runs") { runs.forEach { e -> val o=e.jsonObject; add(buildJsonObject { put("id",o.long("id")); put("name",o.string("name")); put("status",o.string("status")); put("conclusion",o.string("conclusion")); put("head_sha",o.string("head_sha")); put("html_url",o.string("html_url")) }) } } }.toString()
    }

    private suspend fun runDetails(repo: String, runId: Long): String {
        val run=client.request("GET","/repos/$repo/actions/runs/$runId"); requireOk(run.code,run.body)
        val jobs=client.request("GET","/repos/$repo/actions/runs/$runId/jobs?per_page=100"); requireOk(jobs.code,jobs.body)
        val artifacts=client.request("GET","/repos/$repo/actions/runs/$runId/artifacts?per_page=100"); requireOk(artifacts.code,artifacts.body)
        val ro=json.parseToJsonElement(run.body).jsonObject
        val ja=json.parseToJsonElement(jobs.body).jsonObject["jobs"]?.jsonArray ?: JsonArray(emptyList())
        val aa=json.parseToJsonElement(artifacts.body).jsonObject["artifacts"]?.jsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            put("id",runId); put("name",ro.string("name")); put("status",ro.string("status")); put("conclusion",ro.string("conclusion")); put("html_url",ro.string("html_url"))
            putJsonArray("jobs") { ja.forEach { e -> val o=e.jsonObject; add(buildJsonObject { put("id",o.long("id")); put("name",o.string("name")); put("status",o.string("status")); put("conclusion",o.string("conclusion")); putJsonArray("failed_steps") { (o["steps"]?.jsonArray ?: JsonArray(emptyList())).filter { it.jsonObject.string("conclusion") == "failure" }.forEach { s -> val so=s.jsonObject; add(buildJsonObject { put("number",so.long("number")); put("name",so.string("name")) }) } } }) } }
            putJsonArray("artifacts") { aa.forEach { e -> val o=e.jsonObject; add(buildJsonObject { put("id",o.long("id")); put("name",o.string("name")); put("size_in_bytes",o.long("size_in_bytes")); put("expired",o.bool("expired")) }) } }
        }.toString()
    }

    private suspend fun failedLogs(repo: String, runId: Long, maxChars: Int): String {
        val jobs = client.request("GET", "/repos/$repo/actions/runs/$runId/jobs?per_page=100")
        requireOk(jobs.code, jobs.body)
        val failed = (json.parseToJsonElement(jobs.body).jsonObject["jobs"]?.jsonArray
            ?: JsonArray(emptyList())).filter { it.jsonObject.string("conclusion") == "failure" }
        var remaining = maxChars
        val entries = mutableListOf<JsonObject>()
        for (element in failed) {
            if (remaining <= 0) break
            val job = element.jsonObject
            val budget = minOf(remaining, 20_000)
            val response = client.requestBounded(
                "GET", "/repos/$repo/actions/jobs/${job.long("id")}/logs", maxChars = budget
            )
            val focused = response.body.lineSequence().filter { line ->
                val lower = line.lowercase()
                "error" in lower || "failed" in lower || "exception" in lower ||
                    "> task" in lower || "e:" in lower
            }.take(250).joinToString("\n").ifBlank { response.body.take(budget) }
            remaining -= focused.length
            entries += buildJsonObject {
                put("job_id", job.long("id"))
                put("name", job.string("name"))
                put("log", focused)
                put("truncated", response.truncated || response.body.length > focused.length)
            }
        }
        return buildJsonObject {
            put("run_id", runId)
            put("failed_jobs", JsonArray(entries))
            put("truncated", remaining <= 0)
        }.toString()
    }

    private suspend fun dispatch(repo:String,workflow:String,ref:String):String { val r=client.request("POST","/repos/$repo/actions/workflows/$workflow/dispatches",buildJsonObject { put("ref",ref) }); requireOk(r.code,r.body); return buildJsonObject { put("ok",true); put("workflow",workflow); put("ref",ref) }.toString() }
    private fun requireOk(code:Int,body:String) { if(code !in 200..299) error("GitHub HTTP $code: ${body.take(300)}") }
    private fun JsonObject.string(k:String,d:String="")=this[k]?.jsonPrimitive?.contentOrNull ?: d
    private fun JsonObject.long(k:String)=this[k]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.bool(k:String,d:Boolean=false)=this[k]?.jsonPrimitive?.booleanOrNull ?: d
    private fun tool(name:String,description:String,properties:Map<String,ToolProperty>,required:List<String> = emptyList())=ToolDefinition(function=ToolFunction(name=name,description=description,parameters=ToolParameters(properties=properties,required=required)))
    private fun errorJson(message:String)=buildJsonObject { put("ok",false); put("error",message) }.toString()
    override fun handles(name:String)=name in names
}
