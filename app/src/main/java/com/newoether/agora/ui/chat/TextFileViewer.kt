package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun spreadsheet(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("csv", "tsv", "xlsx", "ods")
@Composable fun TextFileViewer(content: String, fileName: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    if (spreadsheet(fileName) && content.startsWith("=== Sheet:")) { SpreadsheetViewer(content, fileName, onClose, modifier); return }
    BackHandler { onClose() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        SelectionContainer { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 88.dp, bottom = 56.dp)) { Text(content, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp) } }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(72.dp).background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fileName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(onClick = onClose, shape = CircleShape, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close", Modifier.padding(12.dp)) }
        }
    }
}
