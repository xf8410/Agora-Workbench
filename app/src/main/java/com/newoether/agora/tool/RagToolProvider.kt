package com.newoether.agora.tool

import com.newoether.agora.api.EmbeddingClient
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.ProviderDefaults
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.data.EmbeddingIndexer
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Conversation-history tools: search / list / read past conversations. */
class RagToolProvider(
    private val conversations: ConversationRepository
) : ToolProvider {
    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.accessPastConversations) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "search_conversations",
                description = "Search past conversations for relevant information. Use this to recall facts, decisions, or context from previous discussions.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "query" to ToolProperty("string", "The search query to find relevant past conversations."),
                        "limit" to ToolProperty("integer", "Maximum number of results (1-20, default 10).")
                    ), required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "list_conversations",
                description = "List all past conversations. Use this to browse conversation history and find conversations to read. Returns conversation IDs, titles, and last-updated timestamps.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "order" to ToolProperty("string", "Sort order by last updated time: 'asc' (oldest first) or 'desc' (newest first). Default: 'desc'."),
                        "limit" to ToolProperty("integer", "Maximum conversations per page (1-50, default 20)."),
                        "offset" to ToolProperty("integer", "Number of conversations to skip for pagination (default 0).")
                    ), required = emptyList()
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "read_conversation",
                description = "Read a specific conversation by its ID. Shows the selected message branch as a linear list with page controls. Use this after list_conversations or search_conversations to read a conversation of interest. Each message includes participant, text, and timestamp.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "conversation_id" to ToolProperty("string", "The conversation ID to read (from list_conversations or search_conversations results)."),
                        "offset" to ToolProperty("integer", "Number of messages to skip for pagination (default 0)."),
                        "limit" to ToolProperty("integer", "Maximum messages per page (1-100, default 50).")
                    ), required = listOf("conversation_id")
                )
            ))
        )
    }

    override fun handles(name: String) = name in setOf("search_conversations", "list_conversations", "read_conversation")

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String = when (name) {
        "search_conversations" -> executeSearchConversations(arguments, ctx)
        "list_conversations" -> executeListConversations(arguments)
        "read_conversation" -> executeReadConversation(arguments)
        else -> "Unknown tool: $name"
    }

    private data class SearchWindow(val conversationId: String, val conversationTitle: String, val messages: List<MessageEntity>, val topScore: Float, val matchCount: Int)

    private suspend fun executeSearchConversations(arguments: String, ctx: GenerationContext): String {
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(arguments.ifBlank { "{}" })
        val query = (args["query"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("type", "search_conversations"); put("error", "no_query") }.toString()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: ctx.searchMatchLimit).coerceIn(1, 30)
        val n = ((args["context_window"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: ctx.searchContextWindow).coerceIn(4, 32)
        val halfN = n / 2
        return try {
            val scored: List<Pair<MessageEntity, Float>> = if (ctx.modelSearchMethod == Constants.SEARCH_METHOD_RAG && ctx.activeEmbeddingConfig != null)
                semanticSearch(query, limit, ctx).filter { it.second >= ctx.ragThreshold }
            else conversations.searchMessages(query, limit).map { it to 1f }
            if (scored.isEmpty()) return buildJsonObject { put("type", "search_conversations"); put("query", query); put("error", "no_results") }.toString()
            val scoreById = scored.associate { it.first.id to it.second }
            val byConversation = scored.groupBy({ it.first.conversationId }, { it.first.id })
            val windows = mutableListOf<SearchWindow>()
            for ((conversationId, matchIds) in byConversation) {
                val conversation = conversations.getSearchableConversation(conversationId) ?: continue
                // Business branch reconstruction requires complete MessageEntity rows. The bounded
                // MessageListRow projection is UI-only and intentionally omits large payloads.
                val allMessages = conversations.getMessagesForConversationSnapshot(conversationId)
                    .filter { it.participant == Participant.USER || it.participant == Participant.MODEL }
                val branch = buildSelectedBranch(allMessages, conversation.selectedBranchesJson)
                val indexById = branch.withIndex().associate { it.value.id to it.index }
                val validMatches = matchIds.filter { it in indexById }
                for (matchId in validMatches) {
                    val center = indexById.getValue(matchId)
                    val start = (center - halfN).coerceAtLeast(0)
                    val end = (center + halfN).coerceAtMost(branch.lastIndex)
                    val messages = branch.subList(start, end + 1).filter {
                        it.text.isNotEmpty() && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
                    }
                    windows += SearchWindow(conversationId, conversation.title, messages, scoreById[matchId] ?: 1f, 1)
                }
            }
            var used = 0
            val selected = windows.sortedByDescending { it.topScore }.mapNotNull { window ->
                val remaining = 200 - used
                if (remaining <= 0) null else window.copy(messages = window.messages.take(remaining)).also { used += it.messages.size }
            }
            val results = buildJsonArray {
                selected.forEach { window -> add(buildJsonObject {
                    put("title", window.conversationTitle); put("conversation_id", window.conversationId)
                    put("top_score", window.topScore); put("match_count", window.matchCount)
                    putJsonArray("messages") { window.messages.forEach { add(messageJson(it)) } }
                }) }
            }
            buildJsonObject { put("type", "search_conversations"); put("query", query); put("results", results) }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("type", "search_conversations"); put("query", query); put("error", "search_error"); put("message", e.message ?: "") }.toString()
        }
    }

    private suspend fun executeListConversations(arguments: String): String {
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(arguments.ifBlank { "{}" })
        val order = ((args["order"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "desc").lowercase()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val offset = ((args["offset"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        return try {
            val sorted = conversations.getSearchableConversationsList().let { if (order == "desc") it.reversed() else it }
            val page = sorted.drop(offset).take(limit)
            buildJsonObject {
                put("type", "list_conversations"); put("total", sorted.size); put("offset", offset); put("limit", limit); put("has_more", offset + limit < sorted.size)
                putJsonArray("conversations") { page.forEach { conversation -> add(buildJsonObject {
                    put("id", conversation.id); put("title", conversation.title); put("timestamp", formatTime(conversation.lastUpdated))
                }) } }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("type", "list_conversations"); put("error", "list_error"); put("message", e.message ?: "") }.toString()
        }
    }

    private suspend fun executeReadConversation(arguments: String): String {
        val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(arguments.ifBlank { "{}" })
        val id = ((args["conversation_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "").trim()
        if (id.isEmpty()) return buildJsonObject { put("type", "read_conversation"); put("error", "missing_conversation_id") }.toString()
        val limit = ((args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 50).coerceIn(1, 100)
        val offset = ((args["offset"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        return try {
            val conversation = conversations.getSearchableConversation(id)
                ?: return buildJsonObject { put("type", "read_conversation"); put("conversation_id", id); put("error", "not_found") }.toString()
            val allMessages = conversations.getMessagesForConversationSnapshot(id)
                .filter { it.participant == Participant.USER || it.participant == Participant.MODEL }
            val branch = buildSelectedBranch(allMessages, conversation.selectedBranchesJson)
                .filter { !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
            val page = branch.drop(offset).take(limit)
            buildJsonObject {
                put("type", "read_conversation"); put("conversation_id", id); put("title", conversation.title)
                put("total_messages", branch.size); put("offset", offset); put("limit", limit); put("has_more", offset + limit < branch.size)
                putJsonArray("messages") { page.forEach { add(messageJson(it)) } }
            }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("type", "read_conversation"); put("conversation_id", id); put("error", "read_error"); put("message", e.message ?: "") }.toString()
        }
    }

    private fun buildSelectedBranch(allMessages: List<MessageEntity>, selectedBranchesJson: String?): List<MessageEntity> {
        val selections = try {
            Json.decodeFromString<Map<String, String>>(selectedBranchesJson ?: "{}").mapKeys { if (it.key == "null") null else it.key }
        } catch (_: Exception) { emptyMap() }
        val byParent = allMessages.groupBy { it.parentId }
        val path = mutableListOf<MessageEntity>()
        var parentId: String? = null
        while (true) {
            val siblings = byParent[parentId].orEmpty()
            if (siblings.isEmpty()) break
            val visible = siblings.filter { !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
            val chosen = (visible.ifEmpty { siblings }).find { it.id == selections[parentId] } ?: (visible.ifEmpty { siblings }).last()
            path += chosen
            parentId = chosen.id
        }
        return path
    }

    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>> = withContext(Dispatchers.IO) {
        val config = ctx.activeEmbeddingConfig ?: return@withContext emptyList()
        val queryEmbedding = if (config.type == com.newoether.agora.data.EmbeddingModelType.LOCAL) {
            if (!LlamaEngine.isModelReady(config.localFilePath)) return@withContext emptyList()
            LlamaEngine.computeEmbedding(query, config.localFilePath)
        } else {
            val key = ctx.embeddingApiKey.ifBlank { return@withContext emptyList() }
            EmbeddingClient.computeEmbedding(query, key, config.remoteModelName, config.remoteBaseUrl.ifBlank { ProviderDefaults.OPENAI_BASE_URL })
        } ?: return@withContext emptyList()
        conversations.getEmbeddingsByModel(config.id).map { embedding ->
            embedding to EmbeddingIndexer.cosineSimilarity(queryEmbedding, EmbeddingIndexer.bytesToFloats(embedding.embedding))
        }.filter { it.second > ctx.ragThreshold }.sortedByDescending { it.second }.take(limit).let { scored ->
            val messages = conversations.getSearchableMessagesByIds(scored.map { it.first.messageId }).associateBy { it.id }
            scored.mapNotNull { (embedding, score) -> messages[embedding.messageId]?.takeIf { it.text.length >= 10 }?.let { it to score } }
        }
    }

    private fun messageJson(message: MessageEntity) = buildJsonObject {
        put("participant", message.participant.name); put("text", message.text); put("timestamp", formatTime(message.timestamp))
    }

    private fun formatTime(timestamp: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(timestamp))
}
