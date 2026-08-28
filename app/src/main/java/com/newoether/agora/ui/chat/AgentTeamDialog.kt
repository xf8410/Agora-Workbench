package com.newoether.agora.ui.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.newoether.agora.R
import com.newoether.agora.data.AgentRepository
import com.newoether.agora.model.Agent
import com.newoether.agora.ui.components.clearFocusOnTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self-contained UI state for [AgentTeamDialog]: wraps the file-backed
 * [AgentRepository] (the same store [com.newoether.agora.viewmodel.MessageGenerationController]'s
 * relay trigger reads at send time) and mirrors it into Compose state. Deliberately does NOT
 * touch ChatViewModel — the repository is context-scoped and cheap, and the relay always
 * re-reads from disk when a message is sent, so UI writes take effect immediately.
 */
private class AgentTeamStore(context: Context) {
    private val repo = AgentRepository(context.applicationContext)

    var agents by mutableStateOf<List<Agent>>(emptyList())
        private set
    var teamIds by mutableStateOf<List<String>>(emptyList())
        private set

    /** Re-read agents + the conversation's team from disk into Compose state. */
    fun reload(conversationId: String?) {
        runCatching {
            agents = repo.loadAgents()
            teamIds = conversationId
                ?.let { id -> repo.loadTeams()[id] }
                .orEmpty()
                .filter { id -> agents.any { it.id == id } }
        }
    }

    fun saveAgent(agent: Agent) {
        runCatching { repo.saveAgents(repo.loadAgents().filterNot { it.id == agent.id } + agent) }
    }

    fun deleteAgent(agentId: String) {
        runCatching {
            repo.saveAgents(repo.loadAgents().filterNot { it.id == agentId })
            // Drop the agent from every team that referenced it.
            val teams = repo.loadTeams()
            val pruned = teams.mapValues { (_, ids) -> ids.filterNot { it == agentId } }
                .filterValues { it.isNotEmpty() }
            if (pruned != teams) repo.saveTeams(pruned)
        }
    }

    fun setTeam(conversationId: String, agentIds: List<String>) {
        runCatching { repo.setTeamFor(conversationId, agentIds) }
    }
}

/**
 * Multi-agent team manager: pick which agents form the OPEN conversation's relay team,
 * and create / edit / delete agents (name, role prompt, model, enabled). The relay itself
 * triggers automatically once the conversation's enabled team has 2+ members.
 *
 * Hosted from [ChatTopBar] (which already knows the open conversation id), so it needs no
 * ViewModel access.
 */
@Composable
internal fun AgentTeamDialog(
    conversationId: String?,
    isNewChatMode: Boolean,
    onDismiss: () -> Unit,
) {
    val store = remember { AgentTeamStore(LocalContext.current) }
    val scope = rememberCoroutineScope()
    val enabledModels by com.newoether.agora.di.AppContainerEnabledModels
    val modelAliases by com.newoether.agora.di.AppContainerModelAliases

    LaunchedEffect(conversationId) {
        withContext(Dispatchers.IO) { store.reload(conversationId) }
    }

    var editing by remember { mutableStateOf<Agent?>(null) }
    var deleting by remember { mutableStateOf<Agent?>(null) }

    fun reloadAfterMutation() {
        scope.launch(Dispatchers.IO) { store.reload(conversationId) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.agent_team_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }

                Text(
                    text = stringResource(R.string.agent_team_conversation_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.agent_team_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isNewChatMode || conversationId == null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.agent_team_new_chat_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                } else {
                    if (store.teamIds.size < 2) {
                        Text(
                            text = stringResource(R.string.agent_team_need_more, 2 - store.teamIds.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    store.agents.forEach { agent ->
                        val checked = agent.id in store.teamIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = if (checked) store.teamIds - agent.id else store.teamIds + agent.id
                                    store.setTeam(conversationId, next)
                                    reloadAfterMutation()
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    val next = if (isChecked) store.teamIds + agent.id else store.teamIds - agent.id
                                    store.setTeam(conversationId, next)
                                    reloadAfterMutation()
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = agentNameLabel(agent),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = agentModelLabel(agent),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.agent_team_library),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            editing = Agent(
                                name = "",
                                rolePrompt = "",
                                providerKey = enabledModels.firstOrNull().orEmpty()
                            )
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.agent_team_add))
                    }
                }

                if (store.agents.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agent_team_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    store.agents.forEach { agent ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = agentNameLabel(agent),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = agentModelLabel(agent),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { editing = agent }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.agent_editor_edit_title),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = { deleting = agent }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { agent ->
        AgentEditorDialog(
            initial = agent,
            models = enabledModels.toList().sorted(),
            aliases = modelAliases,
            onSave = { updated ->
                scope.launch(Dispatchers.IO) {
                    store.saveAgent(updated)
                    store.reload(conversationId)
                }
                editing = null
            },
            onDismiss = { editing = null }
        )
    }

    deleting?.let { agent ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { deleting = null },
            title = { Text(agent.name, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.agent_editor_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            store.deleteAgent(agent.id)
                            store.reload(conversationId)
                        }
                        deleting = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun agentNameLabel(agent: Agent): String =
    if (agent.enabled) agent.name else "${agent.name}  ·  disabled"

private fun agentModelLabel(agent: Agent): String = agent.providerKey.ifBlank { "—" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentEditorDialog(
    initial: Agent,
    models: List<String>,
    aliases: Map<String, String>,
    onSave: (Agent) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var role by remember { mutableStateOf(initial.rolePrompt) }
    var model by remember {
        mutableStateOf(initial.providerKey.ifBlank { models.firstOrNull().orEmpty() })
    }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (initial.name.isBlank()) R.string.agent_editor_new_title
                    else R.string.agent_editor_edit_title
                ),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.agent_editor_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text(stringResource(R.string.agent_editor_role)) },
                    minLines = 2,
                    maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agent_editor_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = model,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.agent_editor_model)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            models.forEach { candidate ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = aliases[candidate] ?: candidate,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    onClick = {
                                        model = candidate
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.agent_editor_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && model.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            rolePrompt = role.trim(),
                            providerKey = model,
                            enabled = enabled
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
