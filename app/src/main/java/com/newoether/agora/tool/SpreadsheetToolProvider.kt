package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.SpreadsheetSidecarReader
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Model-facing bounded reader for workbook sidecars referenced from attachment messages. */
class SpreadsheetToolProvider(context: Context) : ToolProvider {
    private val root = File(context.filesDir, "spreadsheet_parsed").canonicalFile
    private val json = Json { ignoreUnknownKeys = true }
    private val names = setOf("spreadsheet_list_sheets", "spreadsheet_read_range")

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> = listOf(
        tool(
            "spreadsheet_list_sheets",
            "List sheet names and exact row/column counts for an attached workbook sidecar path.",
            mapOf("path" to ToolProperty("string", "The @agora-spreadsheet-sidecar path shown in the attachment context.")),
            listOf("path"),
        ),
        tool(
            "spreadsheet_read_range",
            "Read an exact inclusive cell range from one workbook sheet. Page large sheets with repeated calls; no rows are omitted within the requested range.",
            mapOf(
                "path" to ToolProperty("string", "The @agora-spreadsheet-sidecar path shown in the attachment context."),
                "sheet" to ToolProperty("string", "Exact sheet name."),
                "start_row" to ToolProperty("integer", "One-based first row."),
                "end_row" to ToolProperty("integer", "One-based inclusive last row; at most 500 rows per call."),
                "start_column" to ToolProperty("integer", "One-based first column."),
                "end_column" to ToolProperty("integer", "One-based inclusive last column; at most 200 columns per call."),
            ),
            listOf("path", "sheet", "start_row", "end_row", "start_column", "end_column"),
        ),
    )

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(arguments.ifBlank { "{}" })
        fun text(key: String) = (args[key] as? JsonPrimitive)?.content.orEmpty()
        fun number(key: String) = text(key).toIntOrNull() ?: throw IllegalArgumentException("$key must be an integer")
        val path = resolvePath(text("path"))
        return when (name) {
            "spreadsheet_list_sheets" -> buildJsonObject {
                put("path", path.absolutePath)
                put("sheets", buildJsonArray {
                    SpreadsheetSidecarReader.listSheets(path.absolutePath).forEach { sheet ->
                        add(buildJsonObject {
                            put("name", sheet.name)
                            put("row_count", sheet.rowCount)
                            put("column_count", sheet.columnCount)
                        })
                    }
                })
            }.toString()
            "spreadsheet_read_range" -> {
                val range = SpreadsheetSidecarReader.readRange(
                    path.absolutePath, text("sheet"), number("start_row"), number("end_row"),
                    number("start_column"), number("end_column"),
                )
                buildJsonObject {
                    put("sheet", range.sheet)
                    put("start_row", range.startRow)
                    put("end_row", range.endRow)
                    put("start_column", range.startColumn)
                    put("end_column", range.endColumn)
                    put("rows", buildJsonArray {
                        range.rows.forEach { row -> add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } }) }
                    })
                }.toString()
            }
            else -> "Unknown spreadsheet tool: $name"
        }
    }

    private fun resolvePath(raw: String): File {
        val normalized = raw.removePrefix(com.newoether.agora.model.SPREADSHEET_SIDECAR_PREFIX)
        val file = File(normalized).canonicalFile
        require(file.isFile && file.parentFile == root) { "Workbook path is not an Agora spreadsheet sidecar" }
        return file
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String>,
    ) = ToolDefinition(ToolFunction(name, description, ToolParameters(properties, required)))

    override fun handles(name: String): Boolean = name in names
}
