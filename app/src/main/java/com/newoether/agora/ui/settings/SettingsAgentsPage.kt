package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.AgentRepository
import com.newoether.agora.model.Agent
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.viewmodel.ChatViewModel

// TODO(i18n): this page hardcodes Chinese labels (same precedent as the relay failure
// messages). Move them into strings.xml (values/ + values-zh/ + values-zh-rTW/) when the
// multi-agent feature leaves its first release.

/**
 * Settings page for the multi-agent relay: agent CRUD plus per-conversation team selection.
 *
 * This wires up the previously dead multi-agent pipeline (AgentRepository + AgentRelayRunner +
 * MessageGenerationController.runAgentRelay): without this page there was no way to create an
 * agent or assign a team, so the feature never activated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAgentsPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val agentRepo = remember { AgentRepository(context) }
    val agents by agentRepo.agents.collectAsState()
    val teams by agentRepo.teams.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val enabledModels by viewModel.settings.enabledModels.collectAsState()
    val availableModels by viewModel.settings.availableModels.collectAsState()
    val modelAliases by viewModel.settings.modelAliases.collectAsState()

    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf<Agent?>(null) }
    var deleteCandidate by remember { mutableStateOf<Agent?>(null) }
    var teamPickerFor by remember { mutableStateOf<String?>(null) }

    CollapsingSettingsScaffold(
        title = "多智能体接力",
        onBack = onBack
    ) {
        SettingsGroupColumn {
            SettingsGroup(
                title = "工作方式",
                items = listOf {
                    SettingsItem(
                        headlineContent = { Text("顺序接力") },
                        supportingContent = {
                            Text(
                                "给对话配置团队（≥2 名启用成员）后，发送的消息会按成员顺序依次接力：" +
                                    "每位成员用自己的模型和职责提示词生成一段，后位的能看到前面的输出。" +
                                    "回复按「【成员名】+ 贡献」分段拼接。不配置团队的对话走普通单模型生成。"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Group, null, tint = MaterialTheme.colorScheme.primary) }
                    )
                }
            )

            SettingsGroup(
                title = "智能体（${agents.size}）",
                items = buildList {
                    if (agents.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text("还没有智能体", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                supportingContent = { Text("点击下方“添加智能体”创建第一位成员", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                                modifier = Modifier.heightIn(min = 64.dp)
                            )
                        }
                    } else {
                        agents.forEach { agent ->
                            add {
                                var showMenu by remember { mutableStateOf(false) }
                                SettingsItem(
                                    headlineContent = { Text(agent.name, fontWeight = FontWeight.Medium) },
                                    supportingContent = {
                                        Column {
                                            Text(agent.providerKey, style = MaterialTheme.typography.bodySmall)
                                            if (agent.rolePrompt.isNotBlank()) {
                                                Text(
                                                    agent.rolePrompt.take(80) + if (agent.rolePrompt.length > 80) "…" else "",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    maxLines = 2,
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Switch(
                                                checked = agent.enabled,
                                                onCheckedChange = { enabled ->
                                                    agentRepo.upsertAgent(agent.copy(enabled = enabled))
                                                },
                                                modifier = Modifier.scale(0.75f)
                                            )
                                            Box {
                                                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.Default.MoreVert, "更多", modifier = Modifier.size(18.dp))
                                                }
                                                DropdownMenu(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                    tonalElevation = 16.dp,
                                                    expanded = showMenu,
                                                    onDismissRequest = { showMenu = false },
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("编辑") },
                                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                                        onClick = {
                                                            showMenu = false
                                                            editingAgent = agent
                                                            showEditor = true
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                                        onClick = {
                                                            showMenu = false
                                                            deleteCandidate = agent
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.clickable {
                                        editingAgent = agent
                                        showEditor = true
                                    }
                                )
                            }
                        }
                    }
                    add {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable {
                                    editingAgent = null
                                    showEditor = true
                                }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("添加智能体", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            )

            val convList = conversations.take(30)
            SettingsGroup(
                title = "对话团队（最近 ${convList.size} 个）",
                items = buildList {
                    if (convList.isEmpty()) {
                        add {
                            SettingsItem(
                                headlineContent = { Text("暂无对话", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                modifier = Modifier.heightIn(min = 56.dp)
                            )
                        }
                    } else {
                        convList.forEach { conversation ->
                            add {
                                val teamIds = teams[conversation.id].orEmpty()
                                val byId = agents.associateBy { it.id }
                                val teamNames = teamIds.mapNotNull { byId[it]?.name }
                                SettingsItem(
                                    headlineContent = {
                                        Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    supportingContent = {
                                        Text(
                                            if (teamNames.isEmpty()) "未配置（单模型）" else teamNames.joinToString(" → "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (teamNames.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            else MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.clickable { teamPickerFor = conversation.id }
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    // ── Agent editor dialog ───────────────────────────────────
    if (showEditor) {
        val modelOptions = (enabledModels.ifEmpty { availableModels.values.flatten().toSet() }).toList()
        var name by remember { mutableStateOf(editingAgent?.name.orEmpty()) }
        var providerKey by remember { mutableStateOf(editingAgent?.providerKey.orEmpty()) }
        var rolePrompt by remember { mutableStateOf(editingAgent?.rolePrompt.orEmpty()) }
        var modelMenu by remember { mutableStateOf(false) }
        val displayName: (String) -> String = { key ->
            modelAliases[key] ?: run {
                val parsed = com.newoether.agora.model.ModelId.parse(key)
                "${parsed.apiModelName} (${parsed.providerName})"
            }
        }
        AlertDialog(
            modifier = Modifier.clearFocusOnTap(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showEditor = false; editingAgent = null },
            title = { Text(if (editingAgent == null) "添加智能体" else "编辑智能体", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称（显示在回复分段上）") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ExposedDropdownMenuBox(
                        expanded = modelMenu,
                        onExpandedChange = { modelMenu = it }
                    ) {
                        OutlinedTextField(
                            value = if (providerKey.isBlank()) "" else displayName(providerKey),
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("使用的模型") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                        )
                        ExposedDropdownMenu(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            expanded = modelMenu,
                            onDismissRequest = { modelMenu = false }
                        ) {
                            if (modelOptions.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("没有可用模型——先在“服务商”里配置并启用") },
                                    onClick = { modelMenu = false },
                                    enabled = false
                                )
                            } else {
                                modelOptions.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(displayName(model)) },
                                        onClick = {
                                            providerKey = model
                                            modelMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = rolePrompt,
                        onValueChange = { rolePrompt = it },
                        label = { Text("职责提示词（该成员的系统提示词）") },
                        placeholder = { Text("例如：你是资深架构师，负责审查方案可行性与风险") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 240.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank() && providerKey.isNotBlank()) {
                            agentRepo.upsertAgent(
                                Agent(
                                    id = editingAgent?.id ?: java.util.UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    rolePrompt = rolePrompt.trim(),
                                    providerKey = providerKey,
                                    enabled = editingAgent?.enabled ?: true,
                                )
                            )
                        }
                        showEditor = false
                        editingAgent = null
                    }
                ) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false; editingAgent = null }) {
                    Text(stringResource(R.string.provider_cancel))
                }
            }
        )
    }

    // ── Delete confirmation ───────────────────────────────────
    deleteCandidate?.let { agent ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除智能体", fontWeight = FontWeight.Bold) },
            text = { Text("确定删除「${agent.name}」吗？它在所有对话团队中的位置也会被移除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        agentRepo.deleteAgent(agent.id)
                        deleteCandidate = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.provider_cancel)) }
            }
        )
    }

    // ── Team picker dialog ────────────────────────────────────
    teamPickerFor?.let { conversationId ->
        val conversation = conversations.firstOrNull { it.id == conversationId }
        val currentTeam = teams[conversationId].orEmpty().toSet()
        var selected by remember(conversationId) { mutableStateOf(currentTeam) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { teamPickerFor = null },
            title = { Text("配置团队", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        conversation?.title.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (agents.isEmpty()) {
                        Text("还没有智能体——先添加成员")
                    } else {
                        Text(
                            "勾选参加接力的成员（按智能体列表顺序执行）：",
                            style = MaterialTheme.typography.bodySmall
                        )
                        agents.forEach { agent ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (agent.id in selected) selected - agent.id else selected + agent.id
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Checkbox(checked = agent.id in selected, onCheckedChange = { checked ->
                                    selected = if (checked) selected + agent.id else selected - agent.id
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(agent.name)
                                    Text(
                                        agent.providerKey,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        agentRepo.setTeamFor(conversationId, agents.filter { it.id in selected }.map { it.id })
                        teamPickerFor = null
                    }
                ) { Text(stringResource(R.string.provider_save)) }
            },
            dismissButton = {
                TextButton(onClick = { teamPickerFor = null }) { Text(stringResource(R.string.provider_cancel)) }
            }
        )
    }
}
