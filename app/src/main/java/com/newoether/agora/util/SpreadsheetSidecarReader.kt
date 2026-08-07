package com.newoether.agora.util

import java.io.File

object SpreadsheetSidecarReader {
    data class SheetInfo(val name: String, val rowCount: Int, val columnCount: Int)
    data class RangeResult(val sheet: String, val startRow: Int, val endRow: Int, val startColumn: Int, val endColumn: Int, val rows: List<List<String>>)
    private val marker = Regex("^=== Sheet: (.*) ===$")
    fun listSheets(path: String): List<SheetInfo> {
        val file = File(path); require(file.isFile) { "Spreadsheet sidecar does not exist" }
        val result = mutableListOf<SheetInfo>(); var name: String? = null; var rows = 0; var columns = 0
        fun flush() { name?.let { result += SheetInfo(it, rows, columns) } }
        file.useLines { lines -> lines.forEach { line -> val m = marker.matchEntire(line); if (m != null) { flush(); name = m.groupValues[1]; rows = 0; columns = 0 } else if (name != null) { rows++; columns = maxOf(columns, split(line).size) } } }
        flush(); return result
    }
    fun readRange(path: String, sheet: String, startRow: Int, endRow: Int, startColumn: Int, endColumn: Int): RangeResult {
        require(startRow >= 1 && endRow >= startRow && endRow - startRow < 500) { "Invalid row range or more than 500 rows" }
        require(startColumn >= 1 && endColumn >= startColumn && endColumn - startColumn < 200) { "Invalid column range or more than 200 columns" }
        val file = File(path); require(file.isFile) { "Spreadsheet sidecar does not exist" }
        var current: String? = null; var rowNumber = 0; var found = false; val rows = mutableListOf<List<String>>()
        file.useLines { lines -> for (line in lines) { val m = marker.matchEntire(line); if (m != null) { current = m.groupValues[1]; rowNumber = 0; if (found && current != sheet) break; if (current == sheet) found = true; continue }; if (current != sheet) continue; rowNumber++; if (rowNumber < startRow) continue; if (rowNumber > endRow) break; val cells = split(line); rows += (startColumn..endColumn).map { cells.getOrNull(it - 1).orEmpty() } } }
        require(found) { "Sheet not found: $sheet" }
        return RangeResult(sheet, startRow, startRow + rows.size - 1, startColumn, endColumn, rows)
    }
    private fun split(line: String) = line.split('\t').map { it.replace("\\t", "\t").replace("\\r", "\r").replace("\\n", "\n") }
}
