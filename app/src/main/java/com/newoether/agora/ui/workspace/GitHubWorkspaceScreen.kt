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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.newoether.agora.workspace.GitHubWorkspaceState
import com.newoether.agora.workspace.GitHubWorkspaceStatusLoader
import com.newoether.agora.workspace.GitHubWorkspaceStore
import com.newoether.agora.workspace.WorkspaceLaneId
import com.newoether.agora.workspace.WorkspaceLaneSnapshot
import kotlinx.coroutines.launch

/** A top-level developer workspace. It never renders inside or writes to ordinary chat history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubWorkspaceScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember { GitHubWorkspaceStore(context) }
    val loader = remember { GitHubWorkspaceStatusLoader(context) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(store.load()) }
    val running = remember { mutableStateMapOf<WorkspaceLaneId, Boolean>() }

    fun selectLane(id: WorkspaceLaneId) {
        state = state.copy(selectedLane = id)
        store.save(state)
    }

    fun refreshLane(id: WorkspaceLaneId) {
        if (running[id] == true) return
        val current = state.lanes.first { it.config.id == id }
        running[id] = true
        scope.launch {
            val refreshed = loader.refresh(current.config)
            state = state.copy(lanes = state.lanes.map { if (it.config.id == id) refreshed else it })
            store.save(state)
            running[id] = false
        }
    }

    LaunchedEffect(Unit) {
        state.lanes.forEach { refreshLane(it.config.id) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("工作区"); Text("umaai-rs", style = MaterialTheme.typography.labelSmall) } },
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
                        FilterChip(
                            selected = state.selectedLane == lane.config.id,
                            onClick = { selectLane(lane.config.id) },
                            label = { Text(lane.config.title) },
                            leadingIcon = {
                                Icon(
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
                    loading = running[lane.config.id] == true,
                    onRefresh = { refreshLane(lane.config.id) },
                )
            }
            item {
                val lane = state.lanes.first { it.config.id == state.selectedLane }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("任务通道", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("此通道独立保存基准、目标、任务和运行状态，不会切换另一通道的分支。")
                        if (lane.config.squashRequired) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    "发布通道强制 squash：禁止把实验线的数百个提交普通合并进发布基线。",
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        Button(onClick = { refreshLane(lane.config.id) }, enabled = running[lane.config.id] != true) {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                            Text(" 检查本通道")
                        }
                        Text("创建工作分支、执行 Actions 和提交上游 PR 将在本通道绑定精确 SHA 后开放。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun WorkspaceLaneCard(
    lane: WorkspaceLaneSnapshot,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
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
            if (lane.error != null) {
                Text(lane.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "状态 ${lane.status} · ahead ${lane.aheadBy} · behind ${lane.behindBy}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
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
