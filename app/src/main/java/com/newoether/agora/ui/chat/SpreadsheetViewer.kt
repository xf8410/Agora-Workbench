package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class SpreadsheetSheet(val name: String, val rows: List<List<String>>)
internal fun parseSpreadsheetPreview(content: String): List<SpreadsheetSheet> {
    val marker = Regex("^=== Sheet: (.*) ===$"); val sheets = mutableListOf<SpreadsheetSheet>(); var name: String? = null; var rows = mutableListOf<List<String>>()
    fun flush() { name?.let { sheets += SpreadsheetSheet(it, rows.toList()) }; rows = mutableListOf() }
    content.lineSequence().forEach { line -> val m = marker.matchEntire(line); if (m != null) { flush(); name = m.groupValues[1] } else if (name != null) rows += line.split('\t').map { it.replace("\\t", "\t").replace("\\r", "\r").replace("\\n", "\n") } }; flush(); return sheets
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SpreadsheetViewer(content: String, fileName: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler { onClose() }; val sheets = remember(content) { parseSpreadsheetPreview(content) }; var selectedIndex by remember(content) { mutableIntStateOf(0) }; val selected = sheets.getOrNull(selectedIndex); val columns = remember(selected) { selected?.rows?.maxOfOrNull { it.size } ?: 0 }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(fileName, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") } }
        if (sheets.isNotEmpty()) ScrollableTabRow(selectedIndex, edgePadding = 8.dp, divider = {}) { sheets.forEachIndexed { i, sheet -> Tab(i == selectedIndex, { selectedIndex = i }, text = { Text(sheet.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }) } }
        if (selected == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No spreadsheet sheets found") }
        else Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) { Cell("", 52.dp, true); repeat(columns) { Cell(columnName(it), 132.dp, true) } }
            LazyColumn(Modifier.width(52.dp + 132.dp * columns).weight(1f)) { itemsIndexed(selected.rows, key = { i, _ -> i }) { rowIndex, row -> Row { Cell((rowIndex + 1).toString(), 52.dp, true); repeat(columns) { Cell(row.getOrNull(it).orEmpty(), 132.dp, false) } } } }
        }
    }
}
@Composable private fun Cell(value: String, width: androidx.compose.ui.unit.Dp, header: Boolean) { Surface(Modifier.width(width).heightIn(min = 38.dp), color = if (header) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, border = androidx.compose.foundation.BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) { Text(value, Modifier.padding(horizontal = 7.dp, vertical = 9.dp), fontFamily = if (header) FontFamily.Default else FontFamily.Monospace, fontSize = 12.sp, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal, maxLines = 6, overflow = TextOverflow.Ellipsis) } }
private fun columnName(index: Int): String { var value = index + 1; val out = StringBuilder(); while (value > 0) { value--; out.append(('A'.code + value % 26).toChar()); value /= 26 }; return out.reverse().toString() }
