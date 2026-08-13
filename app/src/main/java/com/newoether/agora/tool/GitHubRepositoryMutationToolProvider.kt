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

internal fun requireRepositoryVisibility(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized == "public" || normalized == "private") {
        "Repository visibility must be public or private"
    }
    return normalized
}

internal fun repositoryVisibilityPatch(visibility: String): JsonObject = buildJsonObject {
    put("visibility", requireRepositoryVisibility(visibility))
}

/** Guarded repository-administration mutations that are too sensitive for ordinary write tools. */
class GitHubRepositoryMutationToolProvider(context: Context) : ToolProvider {
    private val client = GitHubApiClient(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = SET_VISIBILITY,
                description = "Change a repository between public and private after explicit user confirmation. Making it public exposes its code and history.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "repo" to ToolProperty("string", "Repository in owner/name form."),
                        "visibility" to ToolProperty("string", "Target visibility: public or private."),
                    ),
                    required = listOf("repo", "visibility"),
                ),
            ),
        )
    )

    override fun handles(name: String): Boolean = name == SET_VISIBILITY

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name != SET_VISIBILITY) return errorJson("Unknown GitHub repository mutation tool")
        if (!client.isSignedIn()) return errorJson("GitHub is not signed in")
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return errorJson("Invalid tool arguments") }
        fun text(key: String): String = (args[key] as? JsonPrimitive)?.content.orEmpty()

        return try {
            setVisibility(text("repo"), text("visibility"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorJson(e.message ?: "Repository visibility update failed")
        }
    }

    private suspend fun setVisibility(repoArg: String, visibilityArg: String): String {
        val repo = client.validateRepo(repoArg)
        val visibility = requireRepositoryVisibility(visibilityArg)
        val beforeResponse = client.request("GET", "/repos/$repo")
        requireSuccess(beforeResponse.code, beforeResponse.body)
        val before = json.parseToJsonElement(beforeResponse.body).jsonObject
        val current = before["visibility"]?.jsonPrimitive?.content
            ?: if (before["private"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) "private" else "public"

        if (current == visibility) {
            return buildJsonObject {
                put("ok", true)
                put("changed", false)
                put("repo", repo)
                put("visibility", visibility)
                put("private", visibility == "private")
                put("html_url", before["html_url"]?.jsonPrimitive?.content.orEmpty())
            }.toString()
        }

        val warning = if (visibility == "public") {
            "Make $repo PUBLIC. Its code and Git history will become visible to everyone."
        } else {
            "Make $repo PRIVATE. Public forks and cached copies cannot be recalled."
        }
        if (!GitHubMutationConfirmation.confirm(warning)) {
            error("GitHub mutation denied or confirmation unavailable")
        }

        val response = client.request("PATCH", "/repos/$repo", repositoryVisibilityPatch(visibility))
        requireSuccess(response.code, response.body)
        val updated = json.parseToJsonElement(response.body).jsonObject
        val actual = updated["visibility"]?.jsonPrimitive?.content
            ?: if (updated["private"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) "private" else "public"
        check(actual == visibility) { "GitHub returned unexpected repository visibility: $actual" }
        return buildJsonObject {
            put("ok", true)
            put("changed", true)
            put("repo", repo)
            put("previous_visibility", current)
            put("visibility", actual)
            put("private", actual == "private")
            put("html_url", updated["html_url"]?.jsonPrimitive?.content.orEmpty())
        }.toString()
    }

    private fun requireSuccess(code: Int, body: String) {
        if (code !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "GitHub API error"
            error("$message (HTTP $code): ${body.take(300)}")
        }
    }

    private fun errorJson(message: String): String =
        buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()

    private companion object {
        const val SET_VISIBILITY = "github_set_repository_visibility"
    }
}
