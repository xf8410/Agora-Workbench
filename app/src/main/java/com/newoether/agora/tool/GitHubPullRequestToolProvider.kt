package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.viewmodel.GenerationContext
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
    var confirm: (suspend (summary: String) -> Boolean)? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        return listOf(
            tool(CREATE_PR, "Create a pull request from an existing workbench/* branch after user confirmation. This does not merge it.", mapOf(
                "repo" to string("Repository in owner/name form."), "head" to string("Existing source branch; must begin with workbench/."),
                "base" to string("Target branch; defaults to the repository default branch."), "title" to string("Pull request title, 1-200 characters."),
                "body" to string("Optional pull request body, bounded to 20,000 characters."), "draft" to ToolProperty("boolean", "Create as draft; defaults to false."),
            ), listOf("repo", "head", "title")),
            tool(MERGE_PR, "Merge one non-draft pull request after explicit user confirmation and exact head-SHA verification.", mapOf(
                "repo" to string("Repository in owner/name form."), "number" to ToolProperty("integer", "Positive pull request number."),
                "expected_head_sha" to string("Exact 40-character head commit SHA read from github_get_pull_request."),
                "method" to string("Merge method: merge, squash, or rebase. Defaults to squash."), "commit_title" to string("Optional merge commit title, bounded to 200 characters."),
            ), listOf("repo", "number", "expected_head_sha")),
        )
    }

    override fun handles(name: String) = name == CREATE_PR || name == MERGE_PR

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in. Open Settings → GitHub Workbench.")
        val args=runCatching { json.decodeFromString<Map<String,JsonElement>>(arguments.ifBlank { "{}" }) }.getOrElse { return errorJson("Invalid tool arguments") }
        fun text(k:String,d:String="")=(args[k] as? JsonPrimitive)?.content ?: d
        fun int(k:String)=text(k).toIntOrNull() ?: 0
        fun bool(k:String,d:Boolean=false)=text(k).toBooleanStrictOrNull() ?: d
        return try { when(name) {
            CREATE_PR -> createPr(text("repo"),text("head"),text("base"),text("title"),text("body"),bool("draft"))
            MERGE_PR -> mergePr(text("repo"),int("number"),text("expected_head_sha"),text("method","squash"),text("commit_title"))
            else -> errorJson("Unknown GitHub pull-request tool")
        } } catch(e:CancellationException) { throw e } catch(e:Exception) { errorJson(e.message ?: "GitHub PR operation failed") }
    }

    private suspend fun createPr(repo:String, head:String, baseArg:String, title:String, body:String, draft:Boolean):String {
        val r=validRepo(repo); requireWorkbench(head); require(title.trim().length in 1..200) { "PR title must be 1-200 characters" }; require(body.length<=20_000) { "PR body exceeds 20,000 characters" }
        val info=getObject("/repos/$r"); val base=baseArg.ifBlank { info.str("default_branch","main") }; safeRef(base,"base"); require(base!=head) { "PR head and base must differ" }
        confirmed("Create pull request in $r: $head → $base — ${title.trim()}")
        val x=client.request("POST","/repos/$r/pulls",buildJsonObject { put("title",title.trim()); put("head",head); put("base",base); if(body.isNotBlank()) put("body",body); put("draft",draft) }); success(x.code,x.body)
        val p=json.parseToJsonElement(x.body).jsonObject
        return buildJsonObject { put("ok",true); put("number",p.long("number")); put("state",p.str("state")); put("title",p.str("title")); put("draft",p.bool("draft")); put("head",p["head"]?.jsonObject?.str("ref").orEmpty()); put("head_sha",p["head"]?.jsonObject?.str("sha").orEmpty()); put("base",p["base"]?.jsonObject?.str("ref").orEmpty()); put("html_url",p.str("html_url")) }.toString()
    }

    private suspend fun mergePr(repo:String, number:Int, expected:String, method:String, commitTitle:String):String {
        val r=validRepo(repo); require(number>0) { "Pull request number must be positive" }; require(expected.matches(Regex("[0-9a-fA-F]{40}"))) { "expected_head_sha must be an exact 40-character commit SHA" }; require(method in setOf("merge","squash","rebase")) { "Invalid merge method" }; require(commitTitle.length<=200) { "commit_title exceeds 200 characters" }
        val p=getObject("/repos/$r/pulls/$number"); require(p.str("state")=="open") { "Pull request is not open" }; require(!p.bool("draft")) { "Draft pull requests cannot be merged" }
        val h=p["head"]?.jsonObject ?: error("PR has no head"); val b=p["base"]?.jsonObject ?: error("PR has no base"); val head=h.str("ref"); val base=b.str("ref"); val sha=h.str("sha")
        requireWorkbench(head); require(sha.equals(expected,true)) { "Pull request head changed; read it again before merging" }; require(p.str("mergeable")!="false") { "Pull request is not mergeable" }
        confirmed("MERGE pull request $r#$number: $head@$sha → $base using $method")
        val x=client.request("PUT","/repos/$r/pulls/$number/merge",buildJsonObject { put("sha",sha); put("merge_method",method); if(commitTitle.isNotBlank()) put("commit_title",commitTitle) }); success(x.code,x.body); val z=json.parseToJsonElement(x.body).jsonObject
        return buildJsonObject { put("ok",z.bool("merged")); put("merged",z.bool("merged")); put("message",z.str("message")); put("sha",z.str("sha")); put("repo",r); put("number",number); put("head",head); put("base",base) }.toString()
    }

    private suspend fun confirmed(s:String) { if(confirm?.invoke(s)!=true) error("GitHub mutation denied or confirmation unavailable") }
    private suspend fun getObject(path:String):JsonObject { val x=client.request("GET",path); success(x.code,x.body); return json.parseToJsonElement(x.body).jsonObject }
    private fun success(code:Int,body:String) { if(code !in 200..299) { val m=runCatching { json.parseToJsonElement(body).jsonObject.str("message") }.getOrDefault("GitHub API error"); error("$m (HTTP $code)") } }
    private fun validRepo(r:String):String { require(r.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))) { "Invalid owner/name repository" }; return r }
    private fun requireWorkbench(b:String) { require(b.startsWith("workbench/")&&b.length in 11..200) { "PR head must be workbench/*" }; safeRef(b,"head") }
    private fun safeRef(r:String,l:String) { require(r.length in 1..200&&r.matches(Regex("[A-Za-z0-9._/-]+"))&&!r.contains("..")&&!r.startsWith('/')&&!r.endsWith('/')) { "Invalid $l ref" } }
    private fun JsonObject.str(k:String,d:String="")=this[k]?.jsonPrimitive?.content ?: d
    private fun JsonObject.long(k:String,d:Long=0)=this[k]?.jsonPrimitive?.content?.toLongOrNull() ?: d
    private fun JsonObject.bool(k:String,d:Boolean=false)=this[k]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: d
    private fun errorJson(m:String)=buildJsonObject { put("ok",false); put("error",m.take(500)) }.toString()
    private fun tool(n:String,d:String,p:Map<String,ToolProperty>,r:List<String>)=ToolDefinition(function=ToolFunction(name=n,description=d,parameters=ToolParameters(properties=p,required=r)))
    private companion object { const val CREATE_PR="github_create_pull_request"; const val MERGE_PR="github_merge_pull_request" }
}
