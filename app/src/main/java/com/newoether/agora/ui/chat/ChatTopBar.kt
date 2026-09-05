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

/**
 * The chat screen's top bar: a title capsule (drawer menu + brand/conversation title with
 * the context-estimate token subtitle), an actions capsule (system prompt + new chat), and —
 * in an existing conversation — a session usage summary strip that opens the per-request
 * usage detail dialog. Extracted from [ChatApp]; all behavior is routed through callbacks.
 */
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
    val records = remember(allUsage, currentConversationId) {
        allUsage.filter { it.conversationId == currentConversationId }
    }
    val summary = remember(records, currentConversationId) {
        ConversationUsageSummary(currentConversationId.orEmpty(), records)
    }
    var showUsageDetails by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 180.dp)
            .background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                    0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.80f),
                    1.0f to Color.Transparent
                )
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Resolve the active conversation's title; null in new-chat mode OR
            // before the conversation/title has loaded. Both the brand TEXT and the
            // brand font SIZE are gated on this single value, so the title never
            // changes size before the text swaps (no transient "Agora at 17sp").
            val resolvedTitle = if (isNewChatMode) null else {
                currentConversationTitle?.takeIf { it.isNotBlank() }
                    ?: conversations.find { it.id == currentConversationId }?.title?.takeIf { it.isNotBlank() }
            }
            val showBrandTitle = resolvedTitle == null

            // Title capsule: menu + title
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxHeight().widthIn(max = 260.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(5.dp))
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu), modifier = Modifier.size(26.dp))
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    if (showBrandTitle) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = ChatType.brandTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp)
                        )
                    } else {
                        Column(modifier = Modifier.widthIn(max = 180.dp)) {
                            Text(
                                text = resolvedTitle,
                                // Single-line (no token subtitle) uses a slightly-smaller-than-brand
                                // solo size; with the token subtitle stacked below, the compact size.
                                style = if (totalTokens > 0) ChatType.conversationTitle else ChatType.conversationTitleSolo,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (totalTokens > 0) {
                                Text(
                                    text = stringResource(R.string.total_tokens, totalTokens),
                                    style = ChatType.micro,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(20.dp))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Actions capsule: system prompt + new chat
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(5.dp))
                    IconButton(onClick = onSystemPromptClick, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Psychology, contentDescription = stringResource(R.string.system_prompt), modifier = Modifier.size(26.dp))
                    }
                    IconButton(onClick = onNewChat, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.new_chat), modifier = Modifier.size(26.dp))
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                }
            }
        }

        // Session usage strip: cumulative provider-reported usage for THIS conversation
        // (cache hit / uncached input / output), tap for per-request detail.
        if (!isNewChatMode && summary.reportedRecords.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .clickable { showUsageDetails = true },
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 2.dp,
            ) {
                val cache = if (summary.cacheDetailsComplete) compact(summary.reportedCachedTokens) else "—"
                val uncached = if (summary.cacheDetailsComplete) compact(summary.reportedUncachedTokens) else "—"
                val estimated = if (summary.estimatedRecords.isEmpty()) ""
                else "  ·  est.in ${compact(summary.estimatedInputTokens)} / out ${compact(summary.estimatedOutputTokens)}"
                Text(
                    "缓存命中 $cache · 未缓存输入 $uncached · 输出 ${compact(summary.reportedOutputTokens)}$estimated",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = ChatType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showUsageDetails) AlertDialog(
        onDismissRequest = { showUsageDetails = false },
        confirmButton = { TextButton(onClick = { showUsageDetails = false }) { Text(stringResource(R.string.ok)) } },
        title = { Text("会话用量明细") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "当前上下文估算：${compact(totalTokens)}\n以下为累计历史用量，不是当前上下文大小。"
                )
                Spacer(Modifier.height(12.dp))
                if (records.isEmpty()) Text("本会话还没有上报过 provider 用量。")
                records.sortedByDescending { it.createdAt }.forEach { record ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("${record.providerName} · ${record.modelId}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "输入 ${record.inputTokensTotal?.let(::compact) ?: "未提供"} · " +
                            "输出 ${record.outputTokens?.let(::compact) ?: "未提供"} · " +
                            "推理 ${record.reasoningTokens?.let(::compact) ?: "未提供"}"
                    )
                    Text(
                        if (record.cacheDetailsStatus == com.newoether.agora.model.CacheDetailsStatus.PROVIDED)
                            "缓存命中 ${compact(record.inputTokensCached ?: 0)} · 未缓存 ${record.inputTokensUncached?.let(::compact) ?: "未提供"}"
                        else "provider 未提供缓存明细"
                    )
                    if (record.origin == UsageOrigin.LOCALLY_ESTIMATED) {
                        Text("本地估算", color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    )
}

private fun compact(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}
