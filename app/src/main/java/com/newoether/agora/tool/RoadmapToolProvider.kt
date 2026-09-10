package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.RoadmapPriority
import com.newoether.agora.data.RoadmapStatus
import com.newoether.agora.data.RoadmapStore
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lets the assistant capture, review, and maintain the user's planned-feature roadmap.
 * Primary scenario: the user mentions a feature idea in chat and the assistant records it
 * for later implementation instead of it getting lost in the conversation history.
 */
class RoadmapToolProvider(private val store: RoadmapStore) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "roadmap_list",
                description = "List the user's planned-feature roadmap entries, optionally filtered by status (todo/doing/done).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "status" to ToolProperty("string", "Optional filter: todo, doing, or done.")
                    )
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "roadmap_add",
                description = "Add a planned-feature entry to the user's roadmap. Use this whenever the user describes a feature they want implemented later.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "title" to ToolProperty("string", "Short feature title."),
                        "detail" to ToolProperty("string", "Optional longer description or requirements."),
                        "priority" to ToolProperty("string", "Optional: high, normal (default), or low.")
                    ),
                    required = listOf("title")
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "roadmap_update",
                description = "Update one roadmap entry: change title/detail, set status (todo/doing/done), or change priority (high/normal/low).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "id" to ToolProperty("string", "Entry id from roadmap_list."),
                        "title" to ToolProperty("string", "New title (optional)."),
                        "detail" to ToolProperty("string", "New detail (optional)."),
                        "status" to ToolProperty("string", "New status: todo, doing, or done (optional)."),
                        "priority" to ToolProperty("string", "New priority: high, normal, or low (optional).")
                    ),
                    required = listOf("id")
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "roadmap_remove",
                description = "Delete one roadmap entry by id.",
                parameters = ToolParameters(
                    properties = mapOf("id" to ToolProperty("string", "Entry id from roadmap_list.")),
                    required = listOf("id")
                )
            )
        )
    )

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return error("Invalid tool arguments") }
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

        return when (name) {
            "roadmap_list" -> {
                val filter = text("status").lowercase()
                val items = store.list()
                    .filter { filter.isEmpty() || it.status.name.lowercase() == filter }
                if (items.isEmpty()) "(roadmap is empty)" else items.joinToString("\n") { item ->
                    "- [${item.status.name.lowercase()}] ${item.id} (${item.priority.name.lowercase()}) ${item.title}" +
                        if (item.detail.isNotBlank()) "\n  ${item.detail.take(500)}" else ""
                }
            }
            "roadmap_add" -> {
                val title = text("title")
                if (title.isEmpty()) return error("title is required")
                val priority = if (text("priority").isEmpty()) RoadmapPriority.NORMAL
                else parsePriority(text("priority")) ?: return error("priority must be high, normal, or low")
                val item = store.add(title, text("detail"), priority)
                "Added roadmap entry ${item.id}: ${item.title} (${item.status.name.lowercase()}/${item.priority.name.lowercase()})"
            }
            "roadmap_update" -> {
                val id = text("id")
                if (id.isEmpty()) return error("id is required")
                val status = if (text("status").isEmpty()) null
                else parseStatus(text("status")) ?: return error("status must be todo, doing, or done")
                val priority = if (text("priority").isEmpty()) null
                else parsePriority(text("priority")) ?: return error("priority must be high, normal, or low")
                val updated = store.update(
                    id,
                    text("title").ifEmpty { null },
                    text("detail").ifEmpty { null },
                    status,
                    priority
                ) ?: return error("Roadmap entry not found: $id")
                "Updated ${updated.id}: ${updated.title} (${updated.status.name.lowercase()}/${updated.priority.name.lowercase()})"
            }
            "roadmap_remove" -> {
                val id = text("id")
                if (store.remove(id)) "Removed $id" else error("Roadmap entry not found: $id")
            }
            else -> error("Unknown roadmap tool")
        }
    }

    private fun parseStatus(value: String) = when (value.lowercase()) {
        "todo" -> RoadmapStatus.TODO
        "doing" -> RoadmapStatus.DOING
        "done" -> RoadmapStatus.DONE
        else -> null
    }

    private fun parsePriority(value: String) = when (value.lowercase()) {
        "high" -> RoadmapPriority.HIGH
        "normal" -> RoadmapPriority.NORMAL
        "low" -> RoadmapPriority.LOW
        else -> null
    }

    private fun error(message: String) = "Error: $message"

    override fun handles(name: String) =
        name in setOf("roadmap_list", "roadmap_add", "roadmap_update", "roadmap_remove")
}
