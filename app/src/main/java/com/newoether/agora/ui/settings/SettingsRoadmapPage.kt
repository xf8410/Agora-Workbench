package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.RoadmapItem
import com.newoether.agora.data.RoadmapPriority
import com.newoether.agora.data.RoadmapStatus
import com.newoether.agora.data.RoadmapStore
import com.newoether.agora.viewmodel.ChatViewModel

/**
 * Planned-features ("roadmap") management page: a lightweight backlog the user can fill with
 * feature ideas so they stop living in chat history; the assistant can maintain the same list
 * through the roadmap_* tools.
 */
@Composable
fun SettingsRoadmapPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RoadmapStore(context.filesDir) }
    var items by remember { mutableStateOf(store.list()) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var dialogItem by remember { mutableStateOf<RoadmapItem?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun refresh() { items = store.list() }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.settings_roadmap),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.roadmap_add)) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { showAddDialog = true }
            )
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == "all",
                onClick = { filter = "all" },
                label = { Text(stringResource(R.string.roadmap_filter_all)) }
            )
            FilterChip(
                selected = filter == "todo",
                onClick = { filter = "todo" },
                label = { Text(stringResource(R.string.roadmap_status_todo)) }
            )
            FilterChip(
                selected = filter == "doing",
                onClick = { filter = "doing" },
                label = { Text(stringResource(R.string.roadmap_status_doing)) }
            )
            FilterChip(
                selected = filter == "done",
                onClick = { filter = "done" },
                label = { Text(stringResource(R.string.roadmap_status_done)) }
            )
        }

        val visible = items.filter { filter == "all" || it.status.name.lowercase() == filter }
        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.roadmap_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            )
        }
        visible.forEach { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                onClick = { dialogItem = item }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (item.detail.isNotBlank()) {
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3
                        )
                    }
                    Text(
                        text = statusLabel(item.status) + " · " + priorityLabel(item.priority),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (showAddDialog) {
            RoadmapEditDialog(
                initial = null,
                onDismiss = { showAddDialog = false },
                onSave = { title, detail, status, priority ->
                    if (title.isNotBlank()) {
                        store.add(title, detail, priority).also { created ->
                            // An entry created from the dialog may start as doing/done directly.
                            if (created.status != status) {
                                store.update(created.id, status = status)
                            }
                        }
                        refresh()
                    }
                    showAddDialog = false
                },
                onDelete = null
            )
        }
        dialogItem?.let { current ->
            RoadmapEditDialog(
                initial = current,
                onDismiss = { dialogItem = null },
                onSave = { title, detail, status, priority ->
                    store.update(current.id, title = title, detail = detail, status = status, priority = priority)
                    refresh()
                    dialogItem = null
                },
                onDelete = {
                    store.remove(current.id)
                    refresh()
                    dialogItem = null
                }
            )
        }
    }
}

@Composable
private fun statusLabel(status: RoadmapStatus): String = when (status) {
    RoadmapStatus.TODO -> stringResource(R.string.roadmap_status_todo)
    RoadmapStatus.DOING -> stringResource(R.string.roadmap_status_doing)
    RoadmapStatus.DONE -> stringResource(R.string.roadmap_status_done)
}

@Composable
private fun priorityLabel(priority: RoadmapPriority): String = when (priority) {
    RoadmapPriority.HIGH -> stringResource(R.string.roadmap_priority_high)
    RoadmapPriority.NORMAL -> stringResource(R.string.roadmap_priority_normal)
    RoadmapPriority.LOW -> stringResource(R.string.roadmap_priority_low)
}

@Composable
private fun RoadmapEditDialog(
    initial: RoadmapItem?,
    onDismiss: () -> Unit,
    onSave: (title: String, detail: String, status: RoadmapStatus, priority: RoadmapPriority) -> Unit,
    onDelete: (() -> Unit)?
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var detail by remember(initial) { mutableStateOf(initial?.detail.orEmpty()) }
    var status by remember(initial) { mutableStateOf(initial?.status ?: RoadmapStatus.TODO) }
    var priority by remember(initial) { mutableStateOf(initial?.priority ?: RoadmapPriority.NORMAL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.roadmap_add else R.string.roadmap_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.roadmap_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    label = { Text(stringResource(R.string.roadmap_detail_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = status == RoadmapStatus.TODO,
                        onClick = { status = RoadmapStatus.TODO },
                        label = { Text(stringResource(R.string.roadmap_status_todo)) }
                    )
                    FilterChip(
                        selected = status == RoadmapStatus.DOING,
                        onClick = { status = RoadmapStatus.DOING },
                        label = { Text(stringResource(R.string.roadmap_status_doing)) }
                    )
                    FilterChip(
                        selected = status == RoadmapStatus.DONE,
                        onClick = { status = RoadmapStatus.DONE },
                        label = { Text(stringResource(R.string.roadmap_status_done)) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = priority == RoadmapPriority.HIGH,
                        onClick = { priority = RoadmapPriority.HIGH },
                        label = { Text(stringResource(R.string.roadmap_priority_high)) }
                    )
                    FilterChip(
                        selected = priority == RoadmapPriority.NORMAL,
                        onClick = { priority = RoadmapPriority.NORMAL },
                        label = { Text(stringResource(R.string.roadmap_priority_normal)) }
                    )
                    FilterChip(
                        selected = priority == RoadmapPriority.LOW,
                        onClick = { priority = RoadmapPriority.LOW },
                        label = { Text(stringResource(R.string.roadmap_priority_low)) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, detail, status, priority) }) {
                Text(stringResource(R.string.roadmap_save))
            }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.roadmap_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.roadmap_cancel))
                }
            }
        }
    )
}
