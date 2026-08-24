package com.newoether.agora.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
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
import com.newoether.agora.workspace.GitHubWorkspaceStatusLoader
import com.newoether.agora.workspace.GitHubWorkspaceStore
import com.newoether.agora.workspace.WorkspaceAgentRunner
import com.newoether.agora.workspace.WorkspaceLaneId
import com.newoether.agora.workspace.WorkspaceLaneSnapshot
import kotlinx.coroutines.launch

/** Top-level developer workspace. Agent output stays in the lane, never in ordinary chat history. */
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
    val requests = remember { mutableStateMapOf<WorkspaceLaneId, String>() }

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

    LaunchedEffect(Unit) { state.lanes.forEach { refreshLane(it.config.id) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("工作区"); Text(state.workspaceId, style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.lanes.forEach { lane ->
                        val agent by runner.state(state.workspaceId, lane.config.id.name).collectAsState()
                        FilterChip(
                            selected = state.selectedLane == lane.config.id,
                            onClick = { selectLane(lane.config.id) },
                            label = { Text(if (agent.running) "${lane.config.title} · 运行中" else lane.config.title) },
                            leadingIcon = {
                                if (agent.running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(
                                    if (lane.config.id == WorkspaceLaneId.ITERATION) Icons.Default.Build else Icons.Default.RocketLaunch,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
            item {
                val lane = state.lanes.first { it.config.id == state.selectedLane }
                WorkspaceLaneCard(
                    lane = lane,
                    loading = refreshing[lane.config.id] == true,
                    onRefresh = { refreshLane(lane.config.id) },
                )
            }
            item {
                val lane = state.lanes.first { it.config.id == state.selectedLane }
                val laneKey = lane.config.id.name
                val agent by runner.state(state.workspaceId, laneKey).collectAsState()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("GitHub Agent 任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("该任务只绑定当前通道的仓库、基准、目标和独立执行上下文，可调用完整 GitHub 工具。")
                        if (lane.config.squashRequired) {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                                Text(
                                    "发布通道强制 squash：禁止把实验提交历史普通合并到发布基线。",
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = requests[lane.config.id].orEmpty(),
                            onValueChange = { requests[lane.config.id] = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 8,
                            enabled = !agent.running,
                            label = { Text("描述要在当前通道完成的任务") },
                            placeholder = { Text("例如：检查最新 CI，读取失败日志，修复后在 workbench/* 提交并创建上游 PR") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (agent.running) {
                                Button(onClick = { runner.stop(state.workspaceId, laneKey) }) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Text(" 停止")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        runner.run(
                                            workspaceId = state.workspaceId,
                                            laneKey = laneKey,
                                            config = lane.config,
                                            request = requests[lane.config.id].orEmpty(),
                                        )
                                    },
                                    enabled = requests[lane.config.id].orEmpty().isNotBlank(),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Text(" 执行")
                                }
                            }
                            Button(
                                onClick = { refreshLane(lane.config.id) },
                                enabled = refreshing[lane.config.id] != true && !agent.running,
                            ) {
                                Icon(Icons.Default.CloudSync, contentDescription = null)
                                Text(" 检查通道")
                            }
                        }
                        if (agent.lastRequest.isNotBlank()) {
                            Text("最近任务", style = MaterialTheme.typography.labelLarge)
                            Text(agent.lastRequest, style = MaterialTheme.typography.bodySmall)
                        }
                        if (agent.lastResult.isNotBlank()) {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                                Text(agent.lastResult, modifier = Modifier.fillMaxWidth().padding(12.dp))
                            }
                        }
                        agent.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun WorkspaceLaneCard(lane: WorkspaceLaneSnapshot, loading: Boolean, onRefresh: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(lane.config.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(lane.config.description, style = MaterialTheme.typography.bodyMedium)
                }
                if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                else IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
            }
            WorkspaceRef("Fork", lane.config.forkRepository, lane.config.forkBaseBranch, lane.forkHeadSha)
            WorkspaceRef("Upstream", lane.config.upstreamRepository, lane.config.upstreamBaseBranch, lane.upstreamHeadSha)
            if (lane.error != null) Text(lane.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else Text(
                "状态 ${lane.status} · ahead ${lane.aheadBy} · behind ${lane.behindBy}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun WorkspaceRef(label: String, repository: String, branch: String, sha: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("$repository:$branch", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (sha.isNotBlank()) Text(sha.take(12), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
        }
    }
}
