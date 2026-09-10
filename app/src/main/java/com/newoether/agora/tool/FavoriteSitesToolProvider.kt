package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.FavoriteSitesStore
import com.newoether.agora.data.SiteCategory
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lets the assistant maintain the user's favorite-sites list (settings page shows the same
 * data). Primary scenario: the user shares a link in chat and asks to keep it as a favorite;
 * the assistant records it here instead of it getting lost in conversation history.
 */
class FavoriteSitesToolProvider(private val store: FavoriteSitesStore) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "sites_list",
                description = "List the user's favorite sites, optionally filtered by category (mine/external/other).",
                parameters = ToolParameters(
                    properties = mapOf(
                        "category" to ToolProperty("string", "Optional filter: mine, external, or other.")
                    )
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "sites_add",
                description = "Add a site to the user's favorites. Use this whenever the user shares a link they want to keep.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "title" to ToolProperty("string", "Short readable name for the site."),
                        "url" to ToolProperty("string", "Full URL, starting with http:// or https://."),
                        "category" to ToolProperty("string", "Optional: mine (user's own repos), external (other people's), other (default)."),
                        "note" to ToolProperty("string", "Optional note about the site.")
                    ),
                    required = listOf("title", "url")
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "sites_update",
                description = "Update one favorite site: change title/url/category/note.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "id" to ToolProperty("string", "Site id from sites_list."),
                        "title" to ToolProperty("string", "New title (optional)."),
                        "url" to ToolProperty("string", "New URL (optional)."),
                        "category" to ToolProperty("string", "New category: mine, external, or other (optional)."),
                        "note" to ToolProperty("string", "New note (optional).")
                    ),
                    required = listOf("id")
                )
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "sites_remove",
                description = "Delete one favorite site by id.",
                parameters = ToolParameters(
                    properties = mapOf("id" to ToolProperty("string", "Site id from sites_list.")),
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
            "sites_list" -> {
                val filter = text("category").lowercase()
                val items = store.list()
                    .filter { filter.isEmpty() || it.category.name.lowercase() == filter }
                if (items.isEmpty()) "(favorite sites list is empty)" else items.joinToString("\n") { item ->
                    "- [${item.category.name.lowercase()}] ${item.id} ${item.title} ${item.url}" +
                        if (item.note.isNotBlank()) "\n  ${item.note.take(500)}" else ""
                }
            }
            "sites_add" -> {
                val title = text("title")
                val url = text("url")
                if (title.isEmpty()) return error("title is required")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return error("url must start with http:// or https://")
                }
                val category = if (text("category").isEmpty()) SiteCategory.OTHER
                else parseCategory(text("category")) ?: return error("category must be mine, external, or other")
                val item = store.add(title, url, category, text("note"))
                "Added favorite site ${item.id}: ${item.title} (${item.category.name.lowercase()}) ${item.url}"
            }
            "sites_update" -> {
                val id = text("id")
                if (id.isEmpty()) return error("id is required")
                val category = if (text("category").isEmpty()) null
                else parseCategory(text("category")) ?: return error("category must be mine, external, or other")
                val url = text("url")
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                    return error("url must start with http:// or https://")
                }
                val updated = store.update(
                    id,
                    text("title").ifEmpty { null },
                    url.ifEmpty { null },
                    category,
                    text("note").ifEmpty { null }
                ) ?: return error("Favorite site not found: $id")
                "Updated ${updated.id}: ${updated.title} ${updated.url}"
            }
            "sites_remove" -> {
                val id = text("id")
                if (store.remove(id)) "Removed $id" else error("Favorite site not found: $id")
            }
            else -> error("Unknown sites tool")
        }
    }

    private fun parseCategory(value: String) = when (value.lowercase()) {
        "mine" -> SiteCategory.MINE
        "external" -> SiteCategory.EXTERNAL
        "other" -> SiteCategory.OTHER
        else -> null
    }

    private fun error(message: String) = "Error: $message"

    override fun handles(name: String) =
        name in setOf("sites_list", "sites_add", "sites_update", "sites_remove")
}
