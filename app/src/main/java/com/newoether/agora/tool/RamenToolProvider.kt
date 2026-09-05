package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.ramen.RamenDataSourceStore
import com.newoether.agora.ramen.RamenJueceClient
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** LLM tools for the juece-ramen decision datasource on 127.0.0.1:18767. */
class RamenToolProvider : ToolProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private val names = setOf(
        "uma_ramen_status", "uma_ramen_data", "uma_ramen_summary", "uma_ramen_clear",
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        // The datasource switch in 收集数据工作台 is the total switch: disabled means the
        // model sees no ramen tools at all (mirrors the empty-list contract of ToolProvider).
        if (!RamenDataSourceStore.isEnabled()) return emptyList()
        fun string(description: String) = ToolProperty("string", description)
        fun integer(description: String) = ToolProperty("integer", description)
        return listOf(
            tool(
                "uma_ramen_status",
                "Read the juece-ramen datasource status: pending upload queue size, recent cache size, uploaded/dropped totals and whether the GitHub token is configured.",
                emptyMap(),
            ),
            tool(
                "uma_ramen_data",
                "Read recent decision-log records collected by juece-ramen, optionally only records newer than a sequence number.",
                mapOf(
                    "limit" to integer("Maximum number of records to return, 1-${Constants.RAMEN_DATA_LIMIT_MAX}; defaults to ${Constants.RAMEN_DATA_LIMIT_DEFAULT}."),
                    "after" to integer("Only return records with a sequence number greater than this; defaults to 0."),
                ),
            ),
            tool(
                "uma_ramen_summary",
                "Read the current juece-ramen blackboard state summary JSON.",
                emptyMap(),
            ),
            tool(
                "uma_ramen_clear",
                "DESTRUCTIVE: clears ALL in-memory decision data held by juece-ramen (pending upload queue + recent cache); data already uploaded to GitHub is not affected. Always confirm with the user before calling this.",
                emptyMap(),
            ),
        )
    }

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (name !in names) return toolError("Unknown ramen tool")
        if (!RamenDataSourceStore.isEnabled()) {
            return toolError("juece-ramen 数据源未启用：请在 收集数据工作台 打开「连接收集数据源」")
        }
        val args = runCatching {
            json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
        }.getOrElse { return toolError("Invalid tool arguments") }
        val client = RamenJueceClient()
        return runCatching {
            when (name) {
                "uma_ramen_status" -> client.statusRaw()
                "uma_ramen_data" -> {
                    val limit = (args["limit"] as? JsonPrimitive)?.content
                        ?.toIntOrNull() ?: Constants.RAMEN_DATA_LIMIT_DEFAULT
                    val after = (args["after"] as? JsonPrimitive)?.content
                        ?.toLongOrNull() ?: 0L
                    client.dataRaw(limit, after)
                }
                "uma_ramen_summary" -> client.summaryRaw()
                "uma_ramen_clear" -> client.clearDataRaw()
                else -> throw IllegalArgumentException("Unknown ramen tool")
            }
        }.getOrElse { toolError(it.message ?: "juece-ramen request failed") }
    }

    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>) =
        ToolDefinition(function = ToolFunction(name = name, description = description,
            parameters = ToolParameters(properties = properties, required = emptyList())))

    private fun toolError(message: String) = buildJsonObject { put("ok", false); put("error", message) }.toString()

    override fun handles(name: String): Boolean = name in names
}
