package com.newoether.agora.tool

import android.content.Context
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Read-only evidence tracer for public contributor history.
 *
 * This tool never impersonates the contributor and never performs a mutation. It first validates
 * the requested target through the source fork's parent/source metadata, then follows the real
 * fork relationship to inspect the public upstream. Results distinguish verified GitHub identity
 * from model inference.
 */
class PublicContributionTraceToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = NAME,
                description = "Read-only: follow a verified public fork relationship from a source repository to its upstream and collect a contributor's branches, commits, pull requests, and evidence. Never writes code and never impersonates the contributor.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "source_repo" to ToolProperty("string", "The user's or project fork, owner/name."),
                        "target_repo" to ToolProperty("string", "Optional upstream owner/name. Must be the source fork parent or source repository."),
                        "contributor" to ToolProperty("string", "GitHub login to trace, for example muxueliunian."),
                        "limit" to ToolProperty("integer", "Maximum items per category, 1-20."),
                    ),
                    required = listOf("source_repo", "contributor"),
                ),
            ),
        ),
    )

    override fun handles(name: String): Boolean = name == NAME

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != NAME) return error("Unknown public contribution tool")
        return runCatching {
            val args = json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
            val source = client.validateRepo(args.string("source_repo"))
            val contributor = args.string("contributor").trim()
            require(contributor.matches(Regex("[A-Za-z0-9-]{1,39}"))) { "Invalid GitHub contributor login" }
            val limit = args.string("limit").toIntOrNull()?.coerceIn(1, 20) ?: 10
            val requestedTarget = args.string("target_repo").takeIf { it.isNotBlank() }?.let(client::validateRepo)

            val sourceMeta = getObject("/repos/$source")
            val parent = sourceMeta["parent"]?.jsonObject?.string("full_name")
            val origin = sourceMeta["source"]?.jsonObject?.string("full_name")
            val target = requestedTarget ?: parent ?: origin
            require(!target.isNullOrBlank()) { "Source repository has no verified parent/source relationship" }
            require(target == parent || target == origin) {
                "Target repository is not the verified parent/source of the source fork"
            }

            val sourceCommits = commitsByAuthor(source, contributor, limit)
            val targetCommits = commitsByAuthor(target, contributor, limit)
            val sourceBranches = branches(source, limit)
            val targetBranches = branches(target, limit)
            val prs = pullRequests(target, contributor, limit)

            buildJsonObject {
                put("ok", true)
                put("readOnly", true)
                put("authenticatedAs", "current GitHub user or anonymous public access")
                put("contributor", contributor)
                put("sourceRepository", source)
                put("targetRepository", target)
                put("relation", if (target == parent) "fork_parent" else "fork_source")
                putJsonArray("verifiedFacts") {
                    add("Target was reached through GitHub parent/source metadata, not repository-name guessing.")
                    add("Commit author identity is taken from GitHub API author.login when available.")
                    add("No write, branch creation, PR creation, dispatch, or merge operation is exposed by this tool.")
                }
                putJsonArray("sourceBranches") { sourceBranches.forEach { add(it) } }
                putJsonArray("upstreamBranches") { targetBranches.forEach { add(it) } }
                putJsonArray("sourceCommits") { sourceCommits.forEach { add(it) } }
                putJsonArray("upstreamCommits") { targetCommits.forEach { add(it) } }
                putJsonArray("upstreamPullRequests") { prs.forEach { add(it) } }
                putJsonArray("inferenceBoundaries") {
                    add("A commit proves GitHub attribution, not the contributor's private intention.")
                    add("A branch or pull request is not treated as merged unless GitHub reports it as merged.")
                    add("Missing public evidence remains unknown; it is never filled by guessing.")
                }
            }.toString()
        }.getOrElse { error(it.message ?: "Unable to trace public contribution") }
    }

    private suspend fun commitsByAuthor(repo: String, contributor: String, limit: Int): List<JsonElement> {
        val response = client.publicRequest("GET", "/repos/$repo/commits?author=${client.encodeSegment(contributor)}&per_page=$limit")
        requireSuccess(response)
        val array = json.parseToJsonElement(response.body).jsonArray
        return array.take(limit).map { item ->
            val value = item.jsonObject
            buildJsonObject {
                put("sha", value.string("sha"))
                put("message", value["commit"]?.jsonObject?.string("message").orEmpty().take(800))
                put("authorLogin", value["author"]?.jsonObject?.string("login").orEmpty())
                put("commitAuthor", value["commit"]?.jsonObject?.get("author")?.jsonObject?.string("name").orEmpty())
                put("date", value["commit"]?.jsonObject?.get("author")?.jsonObject?.string("date").orEmpty())
                put("url", value.string("html_url"))
            }
        }
    }

    private suspend fun branches(repo: String, limit: Int): List<JsonElement> {
        val response = client.publicRequest("GET", "/repos/$repo/branches?per_page=$limit")
        requireSuccess(response)
        return json.parseToJsonElement(response.body).jsonArray.take(limit).map { item ->
            val value = item.jsonObject
            buildJsonObject {
                put("name", value.string("name"))
                put("sha", value["commit"]?.jsonObject?.string("sha").orEmpty())
                put("protected", value["protected"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false)
            }
        }
    }

    private suspend fun pullRequests(repo: String, contributor: String, limit: Int): List<JsonElement> {
        val response = client.publicRequest("GET", "/repos/$repo/pulls?state=all&per_page=100")
        requireSuccess(response)
        return json.parseToJsonElement(response.body).jsonArray.mapNotNull { item ->
            val value = item.jsonObject
            val user = value["user"]?.jsonObject?.string("login").orEmpty()
            val headUser = value["head"]?.jsonObject?.get("user")?.jsonObject?.string("login").orEmpty()
            if (user != contributor && headUser != contributor) return@mapNotNull null
            buildJsonObject {
                put("number", value["number"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
                put("title", value.string("title"))
                put("state", value.string("state"))
                put("merged", value["merged_at"]?.jsonPrimitive?.content != null)
                put("authorLogin", user)
                put("head", value["head"]?.jsonObject?.string("ref").orEmpty())
                put("base", value["base"]?.jsonObject?.string("ref").orEmpty())
                put("url", value.string("html_url"))
            }
        }.take(limit)
    }

    private suspend fun getObject(path: String): JsonObject {
        val response = client.publicRequest("GET", path)
        requireSuccess(response)
        return json.parseToJsonElement(response.body).jsonObject
    }

    private fun requireSuccess(response: com.newoether.agora.github.GitHubApiResponse) {
        require(response.code in 200..299) { "GitHub public API HTTP ${response.code}: ${response.body.take(300)}" }
    }

    private fun Map<String, JsonElement>.string(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content.orEmpty()

    private fun error(message: String) = buildJsonObject {
        put("ok", false)
        put("readOnly", true)
        put("error", message.take(500))
    }.toString()

    private companion object { const val NAME = "trace_public_contribution" }
}
