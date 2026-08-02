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
        fun s(d:String)=ToolProperty("string",d)
        return listOf(
            tool(CREATE_PR,"Create a pull request from an existing workbench/* branch after user confirmation. This does not merge it.",mapOf("repo" to s("Repository in owner/name form."),"head" to s("Existing source branch; must begin with workbench/."),"base" to s("Target branch; defaults to repository default."),"title" to s("PR title, 1-200 characters."),"body" to s("Optional body, max 20,000 characters."),"draft" to ToolProperty("boolean","Defaults false.")),listOf("repo","head","title")),
            tool(MERGE_PR,"Merge one non-draft pull request after explicit user confirmation and exact head-SHA verification.",mapOf("repo" to s("Repository in owner/name form."),"number" to ToolProperty("integer","Positive PR number."),"expected_head_sha" to s("Exact 40-character SHA from github_get_pull_request."),"method" to s("merge, squash, or rebase; defaults squash."),"commit_title" to s("Optional, max 200 characters.")),listOf("repo","number","expected_head_sha"))
        )
    }
    override fun handles(name:String)=name==CREATE_PR||name==MERGE_PR
    override suspend fun execute(name:String,arguments:String,ctx:GenerationContext):String {
        if(!client.isSignedIn()) return err("GitHub is not signed in")
        val a=runCatching { json.decodeFromString<Map<String,JsonElement>>(arguments.ifBlank { "{}" }) }.getOrElse { return err("Invalid tool arguments") }
        fun t(k:String,d:String="")=(a[k] as? JsonPrimitive)?.content ?: d; fun i(k:String)=t(k).toIntOrNull() ?: 0; fun b(k:String) = t(k).toBooleanStrictOrNull() ?: false
        return try { when(name) { CREATE_PR->create(t("repo"),t("head"),t("base"),t("title"),t("body"),b("draft")); MERGE_PR->merge(t("repo"),i("number"),t("expected_head_sha"),t("method","squash"),t("commit_title")); else->err("Unknown tool") } } catch(e:CancellationException){throw e}catch(e:Exception){err(e.message?:"GitHub PR operation failed")}
    }
    private suspend fun create(repo:String,head:String,baseArg:String,title:String,body:String,draft:Boolean):String {
        val r=repo(repo); wb(head); require(title.trim().length in 1..200); require(body.length<=20_000); val info=get("/repos/$r"); val base=baseArg.ifBlank { info.str("default_branch","main") }; ref(base); require(base!=head)
        confirmed("Create pull request in $r: $head → $base — ${title.trim()}")
        val x=client.request("POST","/repos/$r/pulls",buildJsonObject{put("title",title.trim());put("head",head);put("base",base);if(body.isNotBlank())put("body",body);put("draft",draft)}); ok(x.code,x.body); val p=json.parseToJsonElement(x.body).jsonObject
        return buildJsonObject{put("ok",true);put("number",p.long("number"));put("state",p.str("state"));put("draft",p.bool("draft"));put("head",p["head"]?.jsonObject?.str("ref").orEmpty());put("head_sha",p["head"]?.jsonObject?.str("sha").orEmpty());put("base",p["base"]?.jsonObject?.str("ref").orEmpty());put("html_url",p.str("html_url"))}.toString()
    }
    private suspend fun merge(repo:String,n:Int,expected:String,method:String,title:String):String {
        val r=repo(repo);require(n>0);require(expected.matches(Regex("[0-9a-fA-F]{40}")));require(method in setOf("merge","squash","rebase"));require(title.length<=200);val p=get("/repos/$r/pulls/$n");require(p.str("state")=="open");require(!p.bool("draft"));val h=p["head"]?.jsonObject?:error("No head");val q=p["base"]?.jsonObject?:error("No base");val hr=h.str("ref");val br=q.str("ref");val sha=h.str("sha");wb(hr);require(sha.equals(expected,true)){"PR head changed; read it again"};require(p.str("mergeable")!="false")
        confirmed("MERGE pull request $r#$n: $hr@$sha → $br using $method")
        val x=client.request("PUT","/repos/$r/pulls/$n/merge",buildJsonObject{put("sha",sha);put("merge_method",method);if(title.isNotBlank())put("commit_title",title)});ok(x.code,x.body);val z=json.parseToJsonElement(x.body).jsonObject;return buildJsonObject{put("ok",z.bool("merged"));put("merged",z.bool("merged"));put("message",z.str("message"));put("sha",z.str("sha"));put("repo",r);put("number",n)}.toString()
    }
    private suspend fun confirmed(s:String){if(!GitHubMutationConfirmation.confirm(s))error("GitHub mutation denied or confirmation unavailable")}
    private suspend fun get(p:String):JsonObject{val x=client.request("GET",p);ok(x.code,x.body);return json.parseToJsonElement(x.body).jsonObject}
    private fun ok(c:Int,b:String){if(c !in 200..299)error("${runCatching{json.parseToJsonElement(b).jsonObject.str("message")}.getOrDefault("GitHub API error")} (HTTP $c)")}
    private fun repo(r:String)=r.also{require(it.matches(Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")))}
    private fun wb(r:String){require(r.startsWith("workbench/")&&r.length in 11..200);ref(r)};private fun ref(r:String){require(r.matches(Regex("[A-Za-z0-9._/-]{1,200}"))&&!r.contains("..")&&!r.startsWith('/')&&!r.endsWith('/'))}
    private fun JsonObject.str(k:String,d:String="")=this[k]?.jsonPrimitive?.content?:d;private fun JsonObject.long(k:String)=this[k]?.jsonPrimitive?.content?.toLongOrNull()?:0;private fun JsonObject.bool(k:String)=this[k]?.jsonPrimitive?.content?.toBooleanStrictOrNull()?:false
    private fun err(m:String)=buildJsonObject{put("ok",false);put("error",m.take(500))}.toString();private fun tool(n:String,d:String,p:Map<String,ToolProperty>,r:List<String>)=ToolDefinition(function=ToolFunction(name=n,description=d,parameters=ToolParameters(properties=p,required=r)))
    private companion object{const val CREATE_PR="github_create_pull_request";const val MERGE_PR="github_merge_pull_request"}
}
