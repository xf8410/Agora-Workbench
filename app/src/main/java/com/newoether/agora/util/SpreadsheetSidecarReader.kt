package com.newoether.agora.util

import java.io.File

/** Reads parsed workbook sidecars by sheet and bounded cell range without loading them into Room. */
object SpreadsheetSidecarReader {
    data class SheetInfo(val name: String, val rowCount: Int, val columnCount: Int)
    data class RangeResult(
        val sheet: String,
        val startRow: Int,
        val endRow: Int,
        val startColumn: Int,
        val endColumn: Int,
        val rows: List<List<String>>,
    )

    private val marker = Regex("^=== Sheet: (.*) ===$")

    fun listSheets(path: String): List<SheetInfo> {
        val file = File(path)
        require(file.isFile) { "Spreadsheet sidecar does not exist" }
        val result = mutableListOf<SheetInfo>()
        var name: String? = null
        var rows = 0
        var columns = 0
        fun flush() {
            val current = name ?: return
            result += SheetInfo(current, rows, columns)
        }
        file.useLines { lines ->
            lines.forEach { line ->
                val match = marker.matchEntire(line)
                if (match != null) {
                    flush()
                    name = match.groupValues[1]
                    rows = 0
                    columns = 0
                } else if (name != null) {
                    rows++
                    columns = maxOf(columns, splitRow(line).size)
                }
            }
        }
        flush()
        return result
    }

    /** Rows and columns are one-based and inclusive. Each call is deliberately bounded; callers
     * page until [endRow] reaches the row count returned by [listSheets]. */
    fun readRange(
        path: String,
        sheetName: String,
        startRow: Int,
        endRow: Int,
        startColumn: Int,
        endColumn: Int,
    ): RangeResult {
        require(startRow >= 1 && endRow >= startRow) { "Invalid row range" }
        require(startColumn >= 1 && endColumn >= startColumn) { "Invalid column range" }
        require(endRow - startRow + 1 <= 500) { "A range may contain at most 500 rows" }
        require(endColumn - startColumn + 1 <= 200) { "A range may contain at most 200 columns" }
        val file = File(path)
        require(file.isFile) { "Spreadsheet sidecar does not exist" }
        var currentSheet: String? = null
        var currentRow = 0
        var found = false
        val rows = mutableListOf<List<String>>()
        file.useLines { lines ->
            for (line in lines) {
                val match = marker.matchEntire(line)
                if (match != null) {
                    currentSheet = match.groupValues[1]
                    currentRow = 0
                    if (found && currentSheet != sheetName) break
                    if (currentSheet == sheetName) found = true
                    continue
                }
                if (currentSheet != sheetName) continue
                currentRow++
                if (currentRow < startRow) continue
                if (currentRow > endRow) break
                val cells = splitRow(line)
                rows += (startColumn..endColumn).map { column -> cells.getOrNull(column - 1).orEmpty() }
            }
        }
        require(found) { "Sheet not found: $sheetName" }
        return RangeResult(sheetName, startRow, startRow + rows.size - 1, startColumn, endColumn, rows)
    }

    private fun splitRow(line: String): List<String> = line.split('\t').map(::unescape)
    private fun unescape(value: String): String = value
        .replace("\\t", "\t").replace("\\r", "\r").replace("\\n", "\n")
}
