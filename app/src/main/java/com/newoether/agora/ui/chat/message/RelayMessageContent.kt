package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import com.newoether.agora.R
import com.newoether.agora.model.RelaySections
import com.newoether.agora.ui.theme.ChatType

/**
 * Per-agent sub-bubbles for a multi-agent relay reply. Each `【名字】` section renders as
 * its own inset card headed by the agent name chip; an optional trailing relay-failure
 * note renders as an error strip. Replaces the flat markdown blob for relay messages
 * (keyed by [RelaySections.isRelayModelName] in [AssistantMessageContent]).
 */
@Composable
internal fun RelayMessageContent(
    parsed: RelaySections.Parsed,
    isStreaming: Boolean,
    renderContext: ChatMarkdownRenderContext,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parsed.sections.forEachIndexed { index, section ->
            val isLast = index == parsed.sections.lastIndex
            val sectionStreaming = isStreaming && isLast
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SmartToy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = section.agentName,
                            style = ChatType.meta,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(0.dp).padding(top = 4.dp))
                    SelectionContainer {
                        RecomposeSafeMarkdown(
                            content = section.content,
                            isStreaming = sectionStreaming,
                            modifier = Modifier.fillMaxWidth()
                        ) { text ->
                            MarkdownTextContent(text = text, renderContext = renderContext)
                        }
                    }
                }
            }
        }

        parsed.errorText?.let { reason ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.relay_failed_prefix, reason),
                        style = ChatType.errorBody,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
