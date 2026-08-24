package com.newoether.agora.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.model.Participant
import com.newoether.agora.workspace.GitHubWorkspaceStatusLoader
import com.newoether.agora.workspace.GitHubWorkspaceStore
import com.newoether.agora.workspace.WorkspaceAgentRunner
import com.newoether.agora.workspace.WorkspaceChatMessage
import com.newoether.agora.workspace.WorkspaceLaneId
import com.newoether.agora.workspace.WorkspaceLaneSnapshot
import com.newoether.agora.workspace.WorkspaceStageStatus
import kotlinx.coroutines.launch

/** A separate, persistent two-lane chat surface. Workspace conversations never use main chat UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubWorkspaceScreen(
    runner: WorkspaceAgentRunner,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember { GitHubWorkspaceStore(context) }
    val loader = remember { GitHubWorkspaceStatusLoader(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(store.load()) }
    val refreshing = remember { mutableStateMapOf<WorkspaceLaneId, Boolean>() }
    val drafts = remember { mutableStateMapOf<WorkspaceLaneId, String>() }
    val plan by runner.state(state.workspaceId).collectAsState()
    val selected = state.lanes.first { it.config.id == state.selectedLane }
    val laneKey = selected.config.id.name
    val messages by runner.messages(state.workspaceId, laneKey).collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    val laneRunning = plan.running && plan.activeLaneKey == laneKey

    fun selectLane(id: WorkspaceLaneId) {
        state = state.copy(selectedLane = id)
        store.save(state)
    }

    fun refreshLane(id: WorkspaceLaneId) {
        if (refreshing[id] == true) return
        val current = state.lanes.first { it.config.id == id }
        refreshing[id] = true
        scope.launch {
            val refreshed = loader.refresh(current.config)
            state = state.copy(lanes = state.lanes.map { if (it.config.id == id) refreshed else it })
            store.save(state)
            refreshing[id] = false
        }
    }

    LaunchedEffect(Unit) {
        state.lanes.forEach {
            runner.prepareLane(state.workspaceId, it.config)
            refreshLane(it.config.id)
        }
    }
    LaunchedEffect(messages.size, laneKey) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GitHub 工作区")
                        Text("${state.workspaceId} · ${selected.config.title}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回普通对话")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshLane(selected.config.id) }, enabled = !laneRunning) {
                        if (refreshing[selected.config.id] == true) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else Icon(Icons.Default.Refresh, contentDescription = "刷新通道")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = drafts[selected.config.id].orEmpty(),
                        onValueChange = { drafts[selected.config.id] = it },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 6,
                        enabled = !plan.running,
                        placeholder = { Text("继续和${selected.config.title}对话…") },
                    )
                    if (laneRunning) {
                        IconButton(onClick = { runner.stop(state.workspaceId) }) {
                            Icon(Icons.Default.Stop, contentDescription = "停止")
                        }
                    } else {
                        val draft = drafts[selected.config.id].orEmpty()
                        IconButton(
                            onClick = {
                                runner.send(
                                    workspaceId = state.workspaceId,
                                    lanes = state.lanes.map { it.config },
                                    selectedLaneKey = laneKey,
                                    request = draft,
                                )
                                drafts[selected.config.id] = ""
                            },
                            enabled = draft.isNotBlank() && !plan.running,
                        ) { Icon(Icons.Default.Send, contentDescription = "发送") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.lanes.forEach { lane ->
                    val key = lane.config.id.name
                    val stage = plan.stages[key]?.status
                    val running = plan.running && plan.activeLaneKey == key
                    FilterChip(
                        selected = state.selectedLane == lane.config.id,
                        onClick = { selectLane(lane.config.id) },
                        label = {
                            Text(when {
                                running -> "${lane.config.title} · 运行中"
                                stage == WorkspaceStageStatus.QUEUED -> "${lane.config.title} · 等待中"
                                else -> lane.config.title
                            })
                        },
                        leadingIcon = {
                            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(
                                if (lane.config.id == WorkspaceLaneId.ITERATION) Icons.Default.Build else Icons.Default.RocketLaunch,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }

            LaneHeader(selected, refreshing[selected.config.id] == true)

            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("这是独立的${selected.config.title}对话", fontWeight = FontWeight.Bold)
                    Text(
                        "消息会持续保存在当前通道，不会混入普通聊天或另一通道。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { message -> WorkspaceMessageBubble(message) }
                }
            }
        }
    }
}

@Composable
private fun LaneHeader(lane: WorkspaceLaneSnapshot, loading: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${lane.config.forkRepository}:${lane.config.forkBaseBranch}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "→ ${lane.config.upstreamRepository}:${lane.config.upstreamBaseBranch}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                loading -> Text("正在检查通道…", style = MaterialTheme.typography.labelSmall)
                lane.error != null -> Text(lane.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                else -> Text(
                    "${lane.status} · ahead ${lane.aheadBy} · behind ${lane.behindBy}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceMessageBubble(message: WorkspaceChatMessage) {
    val user = message.participant == Participant.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (user) 0.86f else 0.96f),
            shape = RoundedCornerShape(16.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(message.text.ifBlank { if (message.participant == Participant.ERROR) "执行失败" else "…" })
                if (message.status != com.newoether.agora.model.MessageStatus.SUCCESS) {
                    Spacer(Modifier.height(4.dp))
                    Text(message.status.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
