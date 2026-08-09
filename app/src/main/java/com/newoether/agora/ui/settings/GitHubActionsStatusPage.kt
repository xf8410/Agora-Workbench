package com.newoether.agora.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.newoether.agora.github.GitHubApiClient
import com.newoether.agora.github.GitHubWorkflowRun
import com.newoether.agora.github.recentWorkflowRuns
import kotlinx.coroutines.launch

@Composable
fun GitHubActionsStatusPage(
    client: GitHubApiClient,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("github_actions_status", android.content.Context.MODE_PRIVATE)
    }
    var repository by remember {
        mutableStateOf(preferences.getString("repository", "xf8410/Agora-Workbench").orEmpty())
    }
    var runs by remember { mutableStateOf<List<GitHubWorkflowRun>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasRefreshed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (busy || repository.isBlank()) return
        scope.launch {
            busy = true
            error = null
            try {
                val normalized = client.validateRepo(repository)
                runs = client.recentWorkflowRuns(normalized)
                repository = normalized
                preferences.edit().putString("repository", normalized).apply()
                hasRefreshed = true
            } catch (throwable: Throwable) {
                error = throwable.message ?: "Unable to read GitHub Actions runs"
            } finally {
                busy = false
            }
        }
    }

    CollapsingSettingsLazyScaffold(
        title = "Actions / CI",
        onBack = onBack,
        actions = {
            IconButton(
                enabled = !busy && repository.isNotBlank(),
                onClick = ::refresh,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh workflow runs")
            }
        },
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = repository,
                    onValueChange = { repository = it },
                    label = { Text("Repository (owner/name)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !busy && repository.isNotBlank(),
                    onClick = ::refresh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) "Refreshing…" else "Refresh now")
                }
                Text(
                    "No background monitoring. Runs are requested only when you press refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (hasRefreshed && runs.isEmpty() && error == null) {
                    Text("No workflow runs found.")
                }
            }
        }

        items(runs, key = { it.id }) { run ->
            GitHubWorkflowRunRow(
                run = run,
                onOpen = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(run.htmlUrl)))
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun GitHubWorkflowRunRow(
    run: GitHubWorkflowRun,
    onOpen: () -> Unit,
) {
    val state = run.conclusion ?: run.status
    val stateColor = workflowStateColor(state)
    SettingsGroup(
        title = run.name,
        bottomPadding = 0.dp,
        items = listOf({
            Column(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(state, color = stateColor, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Default.OpenInNew, contentDescription = "Open run on GitHub")
                }
                Text("${run.headBranch ?: "(no branch)"} · ${run.shortSha}", fontFamily = FontFamily.Monospace)
                Text("${run.event} · ${run.updatedAt}", style = MaterialTheme.typography.bodySmall)
                Text("Run ${run.id}", style = MaterialTheme.typography.bodySmall)
            }
        }),
    )
}

@Composable
private fun workflowStateColor(state: String): Color = when (state) {
    "success" -> Color(0xFF2E7D32)
    "failure", "timed_out", "startup_failure" -> MaterialTheme.colorScheme.error
    "cancelled", "skipped", "neutral" -> MaterialTheme.colorScheme.onSurfaceVariant
    "queued", "waiting", "requested", "pending" -> Color(0xFFB26A00)
    "in_progress" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}
