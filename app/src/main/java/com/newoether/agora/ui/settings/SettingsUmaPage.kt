package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.newoether.agora.uma.UmaExportFileManager
import com.newoether.agora.uma.UmaWorkbenchService
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsUmaPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportManager = remember { UmaExportFileManager(context.applicationContext) }
    var exports by remember { mutableStateOf(exportManager.listCompleted()) }
    var saving by remember { mutableStateOf<String?>(null) }
    fun send(action: String) = context.startService(
        Intent(context, UmaWorkbenchService::class.java).setAction(action))
    fun ensureOverlayThenStart() {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")))
        } else UmaWorkbenchService.start(context)
    }
    CollapsingSettingsScaffold(title = "赛马娘工作台", onBack = onBack) {
        SettingsGroupColumn(modifier = Modifier.fillMaxWidth()) {
            SettingsGroup(title = "Session ZIP 下载", items = buildList {
                add {
                    SettingsItem(
                        headlineContent={Text("刷新已导出 ZIP")},
                        supportingContent={Text("扫描 Agora 已完成的 Session ZIP；不重新读取游戏数据")},
                        leadingContent={Icon(Icons.Default.Refresh,null,tint=MaterialTheme.colorScheme.primary)},
                        modifier=Modifier.clickable {
                            exports = exportManager.listCompleted()
                            viewModel.emitSnackbar("找到 ${exports.size} 个已导出 ZIP")
                        },
                    )
                }
                if (exports.isEmpty()) {
                    add {
                        SettingsItem(
                            headlineContent={Text("暂无已完成 ZIP")},
                            supportingContent={Text("先在对话中执行 uma_session_export_zip；完成后回到这里点击刷新")},
                            leadingContent={Icon(Icons.Default.Archive,null,tint=MaterialTheme.colorScheme.secondary)},
                        )
                    }
                } else {
                    exports.forEach { export ->
                        add {
                            val busy = saving == export.file.absolutePath
                            SettingsItem(
                                headlineContent={Text(if (busy) "正在保存…" else "下载 ${export.sessionId}.zip")},
                                supportingContent={Text("${export.byteLength / 1024} KiB · 点击保存到 Download/AgoraUma")},
                                leadingContent={Icon(Icons.Default.Download,null,tint=MaterialTheme.colorScheme.primary)},
                                modifier=Modifier.clickable(enabled = saving == null) {
                                    saving = export.file.absolutePath
                                    scope.launch {
                                        runCatching { exportManager.saveToDownloads(export) }
                                            .onSuccess { saved ->
                                                viewModel.emitSnackbar("已保存：${saved.destination}")
                                            }
                                            .onFailure { failure ->
                                                viewModel.emitSnackbar("保存失败：${failure.message ?: "未知错误"}")
                                            }
                                        saving = null
                                    }
                                },
                            )
                        }
                    }
                }
            })
            SettingsGroup(title = "Agora 内置 SO 连接", items = listOf(
                { SettingsItem(headlineContent={Text("启动监听与 Agora 浮窗")},
                    supportingContent={Text("直接读取 127.0.0.1:18765；赛马娘保持前台，不经过浏览器或其他 App")},
                    leadingContent={Icon(Icons.Default.PlayArrow,null,tint=MaterialTheme.colorScheme.primary)},
                    modifier=Modifier.clickable{ensureOverlayThenStart()}) },
                { SettingsItem(headlineContent={Text("自动分析开关")},
                    supportingContent={Text("关键状态变化后复用 Agora 后台生成引擎；最短间隔 20 秒")},
                    leadingContent={Icon(Icons.Default.Sync,null,tint=MaterialTheme.colorScheme.primary)},
                    modifier=Modifier.clickable{send(UmaWorkbenchService.ACTION_TOGGLE_AUTO)}) },
                { SettingsItem(headlineContent={Text("立即分析")},
                    supportingContent={Text("读取一致快照，并让默认模型使用全部 uma_* 工具分析")},
                    leadingContent={Icon(Icons.Default.AutoAwesome,null,tint=MaterialTheme.colorScheme.primary)},
                    modifier=Modifier.clickable{send(UmaWorkbenchService.ACTION_ANALYZE)}) },
                { SettingsItem(headlineContent={Text("立即刷新")}, supportingContent={Text("只请求当前 /summary，不调用模型")},
                    leadingContent={Icon(Icons.Default.Refresh,null,tint=MaterialTheme.colorScheme.primary)},
                    modifier=Modifier.clickable{send(UmaWorkbenchService.ACTION_REFRESH)}) },
                { SettingsItem(headlineContent={Text("停止工作台")}, supportingContent={Text("停止后台监听、通信观测并移除 Agora 浮窗")},
                    leadingContent={Icon(Icons.Default.Stop,null,tint=MaterialTheme.colorScheme.error)},
                    modifier=Modifier.clickable{UmaWorkbenchService.stop(context)}) }
            ))
            SettingsGroup(title = "通信协议观测", items = listOf(
                { SettingsItem(headlineContent={Text("在游戏浮窗中控制")},
                    supportingContent={Text("浮窗底部可直接开始或停止观测。SO 未连接时会进入准备状态，游戏启动后自动开启；临时断线后也会自动恢复。")},
                    leadingContent={Icon(Icons.Default.Visibility,null,tint=MaterialTheme.colorScheme.primary)},
                    modifier=Modifier.clickable{
                        ensureOverlayThenStart()
                        send(UmaWorkbenchService.ACTION_TOGGLE_CAPTURE)
                        viewModel.emitSnackbar("已切换通信观测；可回到游戏后继续用浮窗控制")
                    }) }
            ))
            SettingsGroup(title = "怎么读取", items = listOf({
                SettingsItem(headlineContent={Text("在 Agora 对话中直接说")},
                    supportingContent={Text("“读取最近的赛马娘通信端点并按顺序整理”。模型会调用 uma_protocol_metadata；要读业务状态则调用 uma_get_snapshot、uma_event_observations 等。")})
            }))
            SettingsGroup(title = "数据范围", items = listOf({
                SettingsItem(headlineContent={Text("完整通信数据交给模型")},
                    supportingContent={Text("协议观测返回完整的 path、header、cookie、token、payload 和 hex，无脱敏。本地采集容量由 hlpatch 单独管理。")})
            }))
        }
    }
}
