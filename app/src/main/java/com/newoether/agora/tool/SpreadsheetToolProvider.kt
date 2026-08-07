package com.newoether.agora.tool

import android.content.Context
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.model.SPREADSHEET_SIDECAR_PREFIX
import com.newoether.agora.util.SpreadsheetSidecarReader
import com.newoether.agora.viewmodel.GenerationContext
import java.io.File
import kotlinx.serialization.json.*

class SpreadsheetToolProvider(context: Context) : ToolProvider {
    private val root = File(context.filesDir, "spreadsheet_parsed").canonicalFile
    private val names = setOf("spreadsheet_list_sheets", "spreadsheet_read_range")
    override fun definitions(ctx: GenerationContext) = listOf(
        tool("spreadsheet_list_sheets", "List all sheets and exact row/column counts for an attached workbook sidecar.", mapOf("path" to ToolProperty("string", "The @agora-spreadsheet-sidecar reference in attachment context.")), listOf("path")),
        tool("spreadsheet_read_range", "Read an exact inclusive range from one workbook sheet. Page repeatedly until all required rows are read.", mapOf("path" to ToolProperty("string", "Workbook sidecar reference."), "sheet" to ToolProperty("string", "Exact sheet name."), "start_row" to ToolProperty("integer", "One-based first row."), "end_row" to ToolProperty("integer", "Inclusive last row, maximum 500 rows."), "start_column" to ToolProperty("integer", "One-based first column."), "end_column" to ToolProperty("integer", "Inclusive last column, maximum 200 columns.")), listOf("path", "sheet", "start_row", "end_row", "start_column", "end_column"))
    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        val args = Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" }); fun text(k: String) = (args[k] as? JsonPrimitive)?.content.orEmpty(); fun int(k: String) = text(k).toIntOrNull() ?: error("$k must be integer"); val file = resolve(text("path"))
        return when (name) {
            "spreadsheet_list_sheets" -> buildJsonObject { put("path", SPREADSHEET_SIDECAR_PREFIX + file.absolutePath); put("sheets", buildJsonArray { SpreadsheetSidecarReader.listSheets(file.absolutePath).forEach { s -> add(buildJsonObject { put("name", s.name); put("row_count", s.rowCount); put("column_count", s.columnCount) }) } }) }.toString()
            "spreadsheet_read_range" -> SpreadsheetSidecarReader.readRange(file.absolutePath, text("sheet"), int("start_row"), int("end_row"), int("start_column"), int("end_column")).let { r -> buildJsonObject { put("sheet", r.sheet); put("start_row", r.startRow); put("end_row", r.endRow); put("start_column", r.startColumn); put("end_column", r.endColumn); put("rows", buildJsonArray { r.rows.forEach { row -> add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } }) } }) }.toString() }
            else -> "Unknown spreadsheet tool: $name"
        }
    }
    private fun resolve(raw: String): File { val f = File(raw.removePrefix(SPREADSHEET_SIDECAR_PREFIX)).canonicalFile; require(f.isFile && f.parentFile == root) { "Not an Agora spreadsheet sidecar" }; return f }
    private fun tool(name: String, description: String, properties: Map<String, ToolProperty>, required: List<String>) = ToolDefinition(ToolFunction(name, description, ToolParameters(properties, required)))
    override fun handles(name: String) = name in names
}
