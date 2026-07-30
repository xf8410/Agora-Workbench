package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.newoether.agora.uma.UmaWorkbenchService
import com.newoether.agora.viewmodel.ChatViewModel

@Composable
fun SettingsUmaPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    fun ensureOverlayThenStart() {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")))
        } else {
            UmaWorkbenchService.start(context)
        }
    }
    CollapsingSettingsScaffold(title = "赛马娘工作台", onBack = onBack) {
        SettingsGroupColumn(modifier = Modifier.fillMaxWidth()) {
            SettingsGroup(title = "Agora 内置 SO 连接", items = listOf(
                {
                    SettingsItem(
                        headlineContent = { Text("启动自动监听与浮窗") },
                        supportingContent = { Text("直接读取 127.0.0.1:18765；赛马娘保持前台，不经过浏览器或其他 App") },
                        leadingContent = { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { ensureOverlayThenStart() },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text("立即刷新") },
                        supportingContent = { Text("请求当前 /summary 快照") },
                        leadingContent = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable {
                            context.startService(Intent(context, UmaWorkbenchService::class.java).setAction(UmaWorkbenchService.ACTION_REFRESH))
                        },
                    )
                },
                {
                    SettingsItem(
                        headlineContent = { Text("停止工作台") },
                        supportingContent = { Text("停止后台监听并移除 Agora 浮窗") },
                        leadingContent = { Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { UmaWorkbenchService.stop(context) },
                    )
                },
            ))
            SettingsGroup(title = "安全边界", items = listOf({
                SettingsItem(
                    headlineContent = { Text("仅开放白名单小端点") },
                    supportingContent = { Text("禁止 /scan、/il2cpp/classes、原始 sniff 和递归对象 dump；普通响应 32 KiB，summary 128 KiB") },
                )
            }))
        }
    }
}
