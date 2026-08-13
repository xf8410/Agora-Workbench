package com.newoether.agora.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.api.SessionUsageRuntime
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ConversationUsageSummary
import com.newoether.agora.model.UsageOrigin
import com.newoether.agora.ui.theme.ChatType

@Composable
internal fun ChatTopBar(
    isNewChatMode: Boolean,
    conversations: List<ChatConversation>,
    currentConversationId: String?,
    currentConversationTitle: String? = null,
    /** Estimate of the currently selected context path, not historical billed usage. */
    totalTokens: Int,
    onOpenDrawer: () -> Unit,
    onSystemPromptClick: () -> Unit,
    onNewChat: () -> Unit,
) {
    val allUsage by SessionUsageRuntime.records().collectAsState()
    val records = remember(allUsage, currentConversationId) { allUsage.filter { it.conversationId == currentConversationId } }
    val summary = remember(records, currentConversationId) { ConversationUsageSummary(currentConversationId.orEmpty(), records) }
    var showUsageDetails by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 180.dp).background(
        Brush.verticalGradient(0f to MaterialTheme.colorScheme.background.copy(.98f), .6f to MaterialTheme.colorScheme.background.copy(.8f), 1f to Color.Transparent)
    )) {
        Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            val resolvedTitle = if (isNewChatMode) null else currentConversationTitle?.takeIf { it.isNotBlank() }
                ?: conversations.find { it.id == currentConversationId }?.title?.takeIf { it.isNotBlank() }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxHeight().widthIn(max = 260.dp)) {
                Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(5.dp)); IconButton(onClick = onOpenDrawer, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Menu, stringResource(R.string.menu), Modifier.size(26.dp)) }; Spacer(Modifier.width(5.dp))
                    if (resolvedTitle == null) Text(stringResource(R.string.app_name), style = ChatType.brandTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    else Column(Modifier.widthIn(max = 180.dp)) {
                        Text(resolvedTitle, style = ChatType.conversationTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Current context estimate: ${compact(totalTokens)}", style = ChatType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(.7f), maxLines = 1)
                    }
                    Spacer(Modifier.width(20.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxHeight()) {
                Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(5.dp)); IconButton(onClick = onSystemPromptClick, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Psychology, stringResource(R.string.system_prompt), Modifier.size(26.dp)) }
                    IconButton(onClick = onNewChat, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Add, stringResource(R.string.new_chat), Modifier.size(26.dp)) }; Spacer(Modifier.width(5.dp))
                }
            }
        }
        if (!isNewChatMode) {
            Surface(
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().clickable { showUsageDetails = true },
                color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp,
            ) {
                val cache = if (summary.cacheDetailsComplete) compact(summary.reportedCachedTokens) else "details not provided"
                val uncached = if (summary.cacheDetailsComplete) compact(summary.reportedUncachedTokens) else "details not provided"
                val estimated = if (summary.estimatedRecords.isEmpty()) "" else "  |  estimated in ${compact(summary.estimatedInputTokens)} / out ${compact(summary.estimatedOutputTokens)}"
                Text("Session: cache hit $cache  |  uncached input $uncached  |  output ${compact(summary.reportedOutputTokens)}$estimated",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = ChatType.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }

    if (showUsageDetails) AlertDialog(
        onDismissRequest = { showUsageDetails = false },
        confirmButton = { TextButton(onClick = { showUsageDetails = false }) { Text(stringResource(R.string.ok)) } },
        title = { Text("Session usage details") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("Current context estimate: ${compact(totalTokens)}\nHistorical usage below is cumulative and is not the current context size.")
                Spacer(Modifier.height(12.dp))
                if (records.isEmpty()) Text("No provider usage has been reported for this session yet.")
                records.sortedByDescending { it.createdAt }.forEach { record ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("${record.providerName} · ${record.modelId}", style = MaterialTheme.typography.titleSmall)
                    Text("input ${record.inputTokensTotal?.let(::compact) ?: "not provided"} · output ${record.outputTokens?.let(::compact) ?: "not provided"} · reasoning ${record.reasoningTokens?.let(::compact) ?: "not provided"}")
                    Text(if (record.cacheDetailsStatus.name == "PROVIDED") "cache hit ${compact(record.inputTokensCached ?: 0)} · uncached ${record.inputTokensUncached?.let(::compact) ?: "not provided"}" else "cache details not provided")
                    if (record.origin == UsageOrigin.LOCALLY_ESTIMATED) Text("Estimated locally", color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    )
}

private fun compact(value: Int): String = when {
    value >= 1_000_000 -> "${"%.1f".format(value / 1_000_000.0)}M"
    value >= 1_000 -> "${"%.1f".format(value / 1_000.0)}K"
    else -> value.toString()
}
