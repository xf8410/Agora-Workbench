package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.github.GitHubRunWatch
import com.newoether.agora.github.GitHubRunWatchManager
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Persistent polling-only Actions watches. No webhook support is present. */
class GitHubWatchToolProvider(context: Context) : ToolProvider {
    private val manager = GitHubRunWatchManager(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val names = setOf(
        "github_watch_workflow_run", "github_list_watches",
        "github_get_watch_result", "github_cancel_watch",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        fun string(description: String) = ToolProperty("string", description)
        return listOf(
            tool("github_watch_workflow_run", "Persistently monitor one GitHub Actions run by bounded REST polling and notify when terminal. No webhook is used.", mapOf(
                "repo" to string("Repository in owner/name form."),
                "run_id" to ToolProperty("integer", "Positive Actions run ID."),
            ), listOf("repo", "run_id")),
            tool("github_list_watches", "List persisted local Actions watches and their verified run identity/status.", emptyMap()),
            tool("github_get_watch_result", "Read one persisted watch result.", mapOf(
                "watch_id" to string("Watch ID returned by github_watch_workflow_run.")), listOf("watch_id")),
            tool("github_cancel_watch", "Stop one local watch. This does not cancel the GitHub Actions run.", mapOf(
                "watch_id" to string("Watch ID to cancel.")), listOf("watch_id")),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching { json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }) }
            .getOrElse { return error("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
        return runCatching {
            when (name) {
                "github_watch_workflow_run" -> {
                    val watch = manager.create(text("repo"), text("run_id").toLongOrNull() ?: 0, ctx.conversationId)
                    json.encodeToString(watch)
                }
                "github_list_watches" -> json.encodeToString(manager.list())
                "github_get_watch_result" -> json.encodeToString(manager.get(text("watch_id")) ?: error("Watch not found"))
                "github_cancel_watch" -> json.encodeToString(manager.cancel(text("watch_id")))
                else -> error("Unknown GitHub watch tool")
            }
        }.getOrElse { error(it.message ?: "GitHub watch operation failed") }
    }

    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String> = emptyList()) =
        ToolDefinition(function = ToolFunction(name = name, description = description,
            parameters = ToolParameters(properties = properties, required = required)))
    private fun error(message: String) = buildJsonObject { put("ok", false); put("error", message.take(500)) }.toString()
    override fun handles(name: String) = name in names
}
