package com.newoether.agora.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.automation.CronExpression
import com.newoether.agora.data.local.TaskEntity
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import com.newoether.agora.ui.settings.CollapsingSettingsLazyScaffold
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.UUID

/**
 * Tasks feature root: a saved prompt + model you can run on demand. List ↔ detail is an
 * in-overlay switch (no nav graph), mirroring how Settings drives its sub-pages. Opening
 * an execution hands the conversation id up to the host to close the overlay and select it.
 */
@Composable
fun TasksScreen(
    viewModel: ChatViewModel,
    initialTaskId: String? = null,
    onInitialTaskHandled: () -> Unit = {},
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val tasks by viewModel.tasks.collectAsState()
    // The task currently open in the detail editor; null = list view. A brand-new task is a
    // draft that only persists once it has a prompt (or is run), so backing out leaves no junk.
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var isNewDraft by remember { mutableStateOf(false) }

    LaunchedEffect(initialTaskId) {
        val id = initialTaskId ?: return@LaunchedEffect
        viewModel.getTask(id)?.let { target ->
            editing = target
            isNewDraft = false
        }
        onInitialTaskHandled()
    }

    AnimatedContent(
        targetState = editing,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(280)) { it / 6 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(280)) { -it / 12 } + fadeOut(tween(180)))
            } else {
                (slideInHorizontally(tween(280)) { -it / 12 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(280)) { it / 6 } + fadeOut(tween(180)))
            }
        },
        label = "tasksListDetail"
    ) { current ->
        if (current == null) {
            TasksListPage(
                viewModel = viewModel,
                tasks = tasks,
                onBack = onBack,
                onNewTask = {
                    editing = TaskEntity(
                        id = UUID.randomUUID().toString(),
                        name = "", prompt = "", cronExpr = "", nextRunAt = 0L
                    )
                    isNewDraft = true
                },
                onOpenTask = { editing = it; isNewDraft = false },
            )
        } else {
            TaskDetailPage(
                viewModel = viewModel,
                task = current,
                isNew = isNewDraft,
                onBack = { editing = null },
                onOpenConversation = onOpenConversation,
            )
        }
    }
}

@Composable
private fun TasksListPage(
    viewModel: ChatViewModel,
    tasks: List<TaskEntity>,
    onBack: () -> Unit,
    onNewTask: () -> Unit,
    onOpenTask: (TaskEntity) -> Unit,
) {
    val running by viewModel.runningTaskIds.collectAsState()
    var pendingDelete by remember { mutableStateOf<TaskEntity?>(null) }

    BackHandler { onBack() }

    CollapsingSettingsLazyScaffold(
        title = stringResource(R.string.tasks),
        onBack = onBack,
        actions = {
            IconButton(onClick = onNewTask) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.task_new))
            }
        }
    ) {
        if (tasks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.task_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                val executions by viewModel.executionSummariesForTask(task.id)
                    .collectAsState(initial = emptyList())
                TaskCard(
                    task = task,
                    isRunning = task.id in running,
                    latestStatus = executions.firstOrNull()?.status,
                    onClick = { onOpenTask(task) },
                    onRun = { viewModel.runTaskNow(task) },
                    onToggleEnabled = { enabled -> viewModel.saveTask(task.copy(enabled = enabled)) },
                    onDelete = { pendingDelete = task },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    pendingDelete?.let { task ->
        val displayName = task.name.ifBlank { stringResource(R.string.task_name_hint) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.task_delete)) },
            text = { Text(stringResource(R.string.task_delete_confirm, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTask(task.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.task_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    isRunning: Boolean,
    latestStatus: MessageStatus?,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var now by remember(task.id, task.nextRunAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id, task.enabled, task.nextRunAt) {
        if (task.enabled && task.nextRunAt > 0L) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.name.ifBlank { stringResource(R.string.task_name_hint) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (task.prompt.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = task.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(5.dp))
                val presetLabel = SCHEDULE_PRESETS.firstOrNull { it.first == task.cronExpr }
                    ?.second
                    ?.let { stringResource(it) }
                val scheduleLabel = presetLabel ?: task.cronExpr
                val nextRunLabel = if (task.enabled && task.nextRunAt > 0L) {
                    stringResource(
                        R.string.task_next_run,
                        formatTaskCountdown(task.nextRunAt - now),
                    )
                } else null
                val scheduleText = listOfNotNull(scheduleLabel, nextRunLabel)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Text(
                    text = scheduleText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (task.enabled) 1f else 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val statusText = when {
                    isRunning -> stringResource(R.string.task_running)
                    latestStatus == MessageStatus.SUCCESS -> stringResource(R.string.task_status_success)
                    latestStatus == MessageStatus.ERROR -> stringResource(R.string.task_status_failed)
                    latestStatus == MessageStatus.STOPPED -> stringResource(R.string.task_status_stopped)
                    else -> null
                }
                if (statusText != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (isRunning) statusText else stringResource(R.string.task_last_run, statusText),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            isRunning -> MaterialTheme.colorScheme.primary
                            latestStatus == MessageStatus.ERROR -> MaterialTheme.colorScheme.error
                            latestStatus == MessageStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.padding(end = 2.dp),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_run_now)) },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            onClick = { menuOpen = false; onRun() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.task_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

internal fun formatTaskCountdown(remainingMs: Long): String {
    val clampedMs = remainingMs.coerceAtLeast(0L)
    val totalSeconds = clampedMs / 1_000L + if (clampedMs % 1_000L == 0L) 0L else 1L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

@Composable
private fun TaskDetailPage(
    viewModel: ChatViewModel,
    task: TaskEntity,
    isNew: Boolean,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val running by viewModel.runningTaskIds.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()

    var name by rememberSaveable(task.id) { mutableStateOf(task.name) }
    var prompt by rememberSaveable(task.id) { mutableStateOf(task.prompt) }
    var modelId by rememberSaveable(task.id) { mutableStateOf(task.modelId) }
    var cronExpr by rememberSaveable(task.id) { mutableStateOf(task.cronExpr) }
    var enabled by rememberSaveable(task.id) { mutableStateOf(task.enabled) }
    var showModelPicker by remember { mutableStateOf(false) }

    val isRunning = task.id in running
    val executions by viewModel.executionSummariesForTask(task.id).collectAsState(initial = emptyList())

    // Persist on the way out, unless this is an untouched new draft (nothing meaningful entered).
    fun current() = task.copy(name = name.trim(), prompt = prompt, modelId = modelId, cronExpr = cronExpr, enabled = enabled)
    fun persistIfMeaningful() {
        val validCron = cronExpr.isBlank() || CronExpression.isValid(cronExpr)
        if (prompt.isNotBlank() && name.isNotBlank() && validCron) viewModel.saveTask(current())
    }
    fun leave() { persistIfMeaningful(); onBack() }

    BackHandler { leave() }

    CollapsingSettingsLazyScaffold(
        title = if (isNew) stringResource(R.string.task_new) else stringResource(R.string.task_edit),
        onBack = { leave() },
        actions = {
            val canRun = name.isNotBlank() && prompt.isNotBlank() &&
                (cronExpr.isBlank() || CronExpression.isValid(cronExpr)) && !isRunning
            IconButton(
                enabled = canRun,
                onClick = {
                    viewModel.saveTask(current())
                    viewModel.runTaskNow(current())
                }
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.task_run_now))
                }
            }
        }
    ) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.task_name)) },
                placeholder = { Text(stringResource(R.string.task_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.task_prompt)) },
                placeholder = { Text(stringResource(R.string.task_prompt_hint)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions.Default,
            )
            Spacer(Modifier.height(14.dp))
            // Model selector row
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { showModelPicker = true },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.task_model), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    val display = modelId?.let { modelAliases[it] ?: ModelId.parse(it).apiModelName }
                        ?: stringResource(R.string.task_model_default)
                    Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { enabled = !enabled },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.task_enabled), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
            Spacer(Modifier.height(24.dp))
            ScheduleSection(
                cronExpr = cronExpr,
                onCronChange = { cronExpr = it },
            )
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.task_execution_log),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            )
        }

        if (executions.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.task_no_executions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        } else {
            items(executions, key = { it.conversation.id }) { execution ->
                ExecutionRow(execution = execution, onClick = { onOpenConversation(execution.conversation.id) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            enabledModels = enabledModels.toList(),
            modelAliases = modelAliases,
            selected = modelId,
            onSelect = { modelId = it; showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }
}

/** Maps a preset cron to its label; order here is the chip order. "" = manual (no schedule). */
private val SCHEDULE_PRESETS: List<Pair<String, Int>> = listOf(
    "" to R.string.task_schedule_manual,
    "0 * * * *" to R.string.task_schedule_hourly,
    "0 9 * * *" to R.string.task_schedule_daily,
    "0 9 * * 1" to R.string.task_schedule_weekly,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSection(cronExpr: String, onCronChange: (String) -> Unit) {
    val presetValues = SCHEDULE_PRESETS.map { it.first }
    var customMode by rememberSaveable { mutableStateOf(cronExpr.isNotBlank() && cronExpr !in presetValues) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.task_schedule),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SCHEDULE_PRESETS.forEach { (value, labelRes) ->
                val selected = !customMode && cronExpr == value
                FilterChip(
                    selected = selected,
                    onClick = { customMode = false; onCronChange(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
            FilterChip(
                selected = customMode,
                onClick = {
                    customMode = true
                    if (cronExpr in presetValues) onCronChange("")
                },
                label = { Text(stringResource(R.string.task_schedule_custom)) },
            )
        }

        if (customMode) {
            Spacer(Modifier.height(12.dp))
            val parsed = remember(cronExpr) { CronExpression.parse(cronExpr) }
            val invalid = cronExpr.isNotBlank() && parsed == null
            OutlinedTextField(
                value = cronExpr,
                onValueChange = onCronChange,
                label = { Text(stringResource(R.string.task_schedule_custom)) },
                placeholder = { Text(stringResource(R.string.task_cron_hint)) },
                singleLine = true,
                isError = invalid,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(6.dp))
            when {
                invalid -> Text(
                    stringResource(R.string.task_cron_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp),
                )
                parsed != null -> {
                    val next = remember(cronExpr) { parsed.next(System.currentTimeMillis()) }
                    if (next != null) {
                        val formatted = remember(next) {
                            java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
                            ).format(java.util.Date(next))
                        }
                        Text(
                            stringResource(R.string.task_next_run, formatted),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionRow(execution: com.newoether.agora.automation.TaskManager.ExecutionSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val statusText = when (execution.status) {
                    MessageStatus.SUCCESS -> stringResource(R.string.task_status_success)
                    MessageStatus.ERROR -> stringResource(R.string.task_status_failed)
                    MessageStatus.SENDING, MessageStatus.THINKING,
                    MessageStatus.TOOL_CALLING, MessageStatus.TRANSCRIBING -> stringResource(R.string.task_running)
                    MessageStatus.STOPPED -> stringResource(R.string.task_status_stopped)
                    else -> stringResource(R.string.task_status_unknown)
                }
                val formattedTime = remember(execution.timestamp) {
                    if (execution.timestamp == 0L) "" else java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT,
                        java.text.DateFormat.SHORT,
                    ).format(java.util.Date(execution.timestamp))
                }
                Text(
                    text = listOf(statusText, formattedTime).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (execution.status) {
                        MessageStatus.ERROR -> MaterialTheme.colorScheme.error
                        MessageStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (execution.preview.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = execution.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    enabledModels: List<String>,
    modelAliases: Map<String, String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.task_model), fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    DropdownRow(
                        label = stringResource(R.string.task_model_default),
                        sub = null,
                        bold = selected == null,
                        onClick = { onSelect(null) },
                    )
                }
                items(enabledModels) { model ->
                    val parsed = ModelId.parse(model)
                    DropdownRow(
                        label = modelAliases[model] ?: parsed.apiModelName,
                        sub = parsed.providerName,
                        bold = selected == model,
                        onClick = { onSelect(model) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DropdownRow(label: String, sub: String?, bold: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (bold) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f) else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}
