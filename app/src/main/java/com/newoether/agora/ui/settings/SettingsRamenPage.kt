package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.newoether.agora.ramen.RamenDataSourceStore
import com.newoether.agora.ramen.RamenGitHubUploader
import com.newoether.agora.ramen.RamenJueceClient
import com.newoether.agora.ramen.RamenStatus
import com.newoether.agora.ramen.RamenUploadResult
import com.newoether.agora.ramen.validateRamenBaseUrl
import com.newoether.agora.util.Constants
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/** Connection state machine of this page: controls whether the status/clear groups are usable. */
private enum class RamenConnectionState { DISABLED, CONNECTING, CONNECTED, FAILED }

/** Standalone /health test feedback shown inside the 测试连接 row subtitle. */
private sealed interface RamenTestState {
    object Idle : RamenTestState
    object Loading : RamenTestState
    data class Success(val version: String) : RamenTestState
    data class Failure(val reason: String) : RamenTestState
}

/** Warning orange for "token not configured" — not part of the Material scheme. */
private val RamenTokenWarningColor = Color(0xFFF57C00)

@Composable
fun SettingsRamenPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(RamenDataSourceStore.isEnabled()) }
    var baseUrl by remember { mutableStateOf(RamenDataSourceStore.baseUrl()) }
    var connectionState by remember {
        mutableStateOf(
            if (RamenDataSourceStore.isEnabled()) RamenConnectionState.CONNECTING else RamenConnectionState.DISABLED,
        )
    }
    var testState by remember { mutableStateOf<RamenTestState>(RamenTestState.Idle) }
    var status by remember { mutableStateOf<RamenStatus?>(null) }
    var statusLoading by remember { mutableStateOf(false) }
    var showAddressDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var clearInProgress by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var lastUpload by remember { mutableStateOf<RamenUploadResult?>(null) }
    val connected = connectionState == RamenConnectionState.CONNECTED

    fun client() = RamenJueceClient(baseUrl.trim())

    fun refreshStatus() {
        statusLoading = true
        scope.launch {
            runCatching { client().status() }
                .onSuccess { status = it }
                .onFailure { viewModel.emitSnackbar("状态刷新失败：${it.message ?: "未知错误"}") }
            statusLoading = false
        }
    }

    /** Shared probe: the switch uses it to open the connection, 测试连接 uses it as a one-shot check. */
    fun probe() {
        testState = RamenTestState.Loading
        if (enabled) connectionState = RamenConnectionState.CONNECTING
        scope.launch {
            runCatching { client().health() }
                .onSuccess { health ->
                    testState = RamenTestState.Success(health.version)
                    if (enabled) {
                        connectionState = RamenConnectionState.CONNECTED
                        refreshStatus()
                    }
                }
                .onFailure { error ->
                    testState = RamenTestState.Failure(error.message ?: "未知错误")
                    if (enabled) connectionState = RamenConnectionState.FAILED
                }
        }
    }

    fun setEnabledValue(value: Boolean) {
        enabled = value
        RamenDataSourceStore.setEnabled(value)
        if (value) {
            probe()
        } else {
            connectionState = RamenConnectionState.DISABLED
            status = null
        }
    }

    fun saveAddress(value: String) {
        val normalized = value.trim()
        baseUrl = normalized
        RamenDataSourceStore.setBaseUrl(normalized)
        if (enabled) probe()
    }

    fun clearPeerData() {
        clearInProgress = true
        scope.launch {
            runCatching { client().clearData() }
                .onSuccess {
                    viewModel.emitSnackbar("已清空")
                    refreshStatus()
                }
                .onFailure { viewModel.emitSnackbar("清空失败：${it.message ?: "未知错误"}") }
            clearInProgress = false
        }
    }

    /** Full pull from the peer → one JSONL file → fixed GitHub repository. Never clears the peer. */
    fun uploadToGitHub() {
        uploading = true
        scope.launch {
            runCatching {
                RamenGitHubUploader(context).uploadAll(RamenJueceClient(baseUrl.trim()))
            }.onSuccess { result ->
                if (result.recordCount == 0) {
                    viewModel.emitSnackbar("对端没有可上传的数据")
                } else {
                    lastUpload = result
                    viewModel.emitSnackbar("已上传 ${result.recordCount} 条 → ${result.path}")
                }
            }.onFailure { error ->
                viewModel.emitSnackbar("上传失败：${error.message ?: "未知错误"}")
            }
            uploading = false
        }
    }

    LaunchedEffect(Unit) {
        if (enabled) probe()
    }

    if (showAddressDialog) {
        var draft by remember { mutableStateOf(baseUrl) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = { Text("数据源地址") },
            text = {
                val err = error
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            error = validateRamenBaseUrl(it)
                        },
                        singleLine = true,
                        label = { Text("juece-ramen 地址") },
                        placeholder = { Text(com.newoether.agora.util.Constants.RAMEN_DEFAULT_BASE_URL) },
                        isError = err != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (err != null) {
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = validateRamenBaseUrl(draft)
                    if (err != null) {
                        error = err
                    } else {
                        showAddressDialog = false
                        saveAddress(draft)
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAddressDialog = false }) { Text("取消") }
            },
        )
    }

    if (showClearDialog) {
        val counts = status
        AlertDialog(
            onDismissRequest = { if (!clearInProgress) showClearDialog = false },
            title = { Text("清空收集数据？") },
            text = {
                Text(
                    if (counts != null) {
                        "将删除 juece-ramen 内存中的全部数据（待上传 ${counts.queueLen} 条 + 最近缓存 ${counts.recentLen} 条），此操作不可恢复。已上传到 GitHub 的数据不受影响。"
                    } else {
                        "将删除 juece-ramen 内存中的全部数据（当前内存中的全部决策数据），此操作不可恢复。已上传到 GitHub 的数据不受影响。"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        clearPeerData()
                    },
                    enabled = !clearInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    CollapsingSettingsScaffold(title = "收集数据工作台", onBack = onBack) {
        SettingsGroupColumn(modifier = Modifier.fillMaxWidth()) {
            SettingsGroup(title = "juece-ramen 连接", items = listOf(
                { SettingsItem(
                    headlineContent = { Text("连接收集数据源") },
                    supportingContent = { Text("直接读取 127.0.0.1:18767；juece-ramen 保持运行，数据只在它内存里") },
                    leadingContent = { Icon(Icons.Default.CallReceived, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Switch(checked = enabled, onCheckedChange = { setEnabledValue(it) }) },
                    modifier = Modifier.clickable { setEnabledValue(!enabled) },
                ) },
                { SettingsItem(
                    headlineContent = { Text("数据源地址") },
                    supportingContent = { Text(baseUrl) },
                    leadingContent = { Icon(Icons.Default.Link, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { showAddressDialog = true },
                ) },
                { SettingsItem(
                    headlineContent = { Text("测试连接") },
                    supportingContent = {
                        when (val current = testState) {
                            is RamenTestState.Success -> Text(
                                if (current.version.isNotBlank()) "已连接 · v${current.version}" else "已连接",
                                color = MaterialTheme.colorScheme.primary,
                            )
                            is RamenTestState.Failure -> Text(
                                "连接失败 · ${current.reason}",
                                color = MaterialTheme.colorScheme.error,
                            )
                            RamenTestState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("正在测试…")
                            }
                            RamenTestState.Idle -> Text("向对端发一次 /health，成功会显示版本")
                        }
                    },
                    leadingContent = { Icon(Icons.Default.NetworkCheck, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = testState != RamenTestState.Loading) { probe() },
                ) },
            ))
            val statusOverviewItem: @Composable () -> Unit = if (connected) {
                {
                    SettingsItem(
                        headlineContent = { Text("状态总览") },
                        supportingContent = {
                            val current = status
                            when {
                                current != null -> Column {
                                    if (current.persistedLen != null) {
                                        val runs = current.persistedRuns
                                        Text(
                                            if (runs != null) "持久化数据 ${current.persistedLen} 条（$runs 个 run 文件）"
                                            else "持久化数据 ${current.persistedLen} 条",
                                        )
                                    } else {
                                        Text("最近缓存 ${current.recentLen} 条")
                                    }
                                    Text("待上传队列 ${current.queueLen} 条")
                                    Text("已上传 ${current.uploadedTotal} 条")
                                    Text("已丢弃 ${current.droppedTotal} 条")
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        if (current.tokenConfigured) "GitHub token：已配置"
                                        else "GitHub token：未配置，上传保底通道不工作",
                                        color = if (current.tokenConfigured) MaterialTheme.colorScheme.onSurfaceVariant
                                        else RamenTokenWarningColor,
                                    )
                                }
                                statusLoading -> Text("正在获取…")
                                else -> Text("尚未获取，点击「立即刷新」")
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Analytics, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            if (statusLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        },
                    )
                }
            } else {
                {
                    SettingsItem(
                        headlineContent = { Text("未连接数据源") },
                        supportingContent = { Text("未连接数据源，先在上方启用连接") },
                        leadingContent = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            SettingsGroup(title = "数据状态", items = listOf(
                statusOverviewItem,
                { SettingsItem(
                    headlineContent = { Text("立即刷新") },
                    supportingContent = { Text("只请求 /status，不调用模型") },
                    leadingContent = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = connected && !statusLoading) { refreshStatus() },
                ) },
                { SettingsItem(
                    headlineContent = { Text("上传到 GitHub") },
                    supportingContent = {
                        val last = lastUpload
                        Column {
                            Text("拉取对端全量决策数据，打包 JSONL 上传到 ${Constants.RAMEN_UPLOAD_REPO}；上传后不清空对端数据")
                            if (last != null) {
                                Text(
                                    "上次上传：${last.path} · ${last.recordCount} 条",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        if (uploading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    },
                    modifier = Modifier.clickable(enabled = connected && !uploading) { uploadToGitHub() },
                ) },
            ))
            SettingsGroup(title = "数据管理", items = listOf(
                { SettingsItem(
                    headlineContent = { Text("清空对端数据", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("删除 juece-ramen 内存中的全部决策数据（队列+缓存），不影响已上传到 GitHub 的数据") },
                    leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    trailingContent = {
                        if (clearInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    },
                    modifier = Modifier.clickable(enabled = connected && !clearInProgress) { showClearDialog = true },
                ) },
            ))
            SettingsGroup(title = "怎么读取", items = listOf({
                SettingsItem(
                    headlineContent = { Text("在 Agora 对话中直接说") },
                    supportingContent = {
                        Column {
                            Text("模型会自动调用对应工具，例如：")
                            Text("「看看收集数据状态」→ uma_ramen_status")
                            Text("「读一下最近的决策数据」→ uma_ramen_data")
                            Text("「当前黑板什么情况」→ uma_ramen_summary")
                            Text("「清空收集的数据」→ uma_ramen_clear（会清空对端全部内存数据，会再向你确认）")
                        }
                    },
                )
            }))
            SettingsGroup(title = "数据说明", items = listOf({
                SettingsItem(
                    headlineContent = { Text("内存收集，双路保存") },
                    supportingContent = {
                        Text("游戏回合数据由 juece-ramen 收集在内存中，同时走两条路：上传 GitHub 做云端保底；本接口供 Agora 实时读取。这里的删除和卸载 juece-ramen 都只影响手机内存数据。")
                    },
                )
            }))
        }
    }
}
