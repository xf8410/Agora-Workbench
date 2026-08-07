package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.newoether.agora.ui.theme.MonoFamily

private fun isMarkdownFile(fileName: String): Boolean =
    fileName.endsWith(".md", true) || fileName.endsWith(".markdown", true)

private fun isSpreadsheetFile(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in setOf("csv", "tsv", "xlsx", "ods")

@Composable
fun TextFileViewer(
    content: String,
    fileName: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isSpreadsheetFile(fileName) && content.startsWith("=== Sheet:")) {
        SpreadsheetViewer(content = content, fileName = fileName, onClose = onClose, modifier = modifier)
        return
    }

    BackHandler { onClose() }
    val isMarkdown = remember(fileName) { isMarkdownFile(fileName) }
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding()
    ) {
        if (isMarkdown) {
            val typography = MaterialTheme.typography
            val viewerTypography = markdownTypography(
                text = typography.bodyLarge,
                h1 = typography.headlineMedium,
                h2 = typography.headlineSmall,
                h3 = typography.titleLarge,
                h4 = typography.titleMedium,
                h5 = typography.titleSmall,
                h6 = typography.titleSmall,
                code = typography.bodyMedium.copy(fontFamily = MonoFamily, fontSize = 13.sp),
                inlineCode = typography.bodyMedium.copy(fontFamily = MonoFamily, fontSize = 13.sp),
            )
            SelectionContainer {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 96.dp, bottom = 56.dp)
                ) {
                    Markdown(
                        content = content,
                        modifier = Modifier.fillMaxWidth(),
                        typography = viewerTypography,
                        padding = markdownPadding(block = 7.dp),
                    )
                }
            }
        } else {
            SelectionContainer {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 96.dp, bottom = 56.dp)
                ) {
                    Text(
                        content,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().height(96.dp).align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                        0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                        1f to Color.Transparent,
                    )
                )
        )
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Surface(onClick = onClose, shape = CircleShape, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.padding(12.dp))
            }
        }
    }
}
