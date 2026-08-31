package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.model.Agent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Small JSON codec + file-backed repository for agents and per-conversation teams. */
object AgentCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encodeAgents(agents: List<Agent>): String = json.encodeToString(agents)

    fun decodeAgents(raw: String?): List<Agent> =
        if (raw.isNullOrBlank()) emptyList()
        else try {
            json.decodeFromString<List<Agent>>(raw)
        } catch (_: Exception) {
            emptyList()
        }

    fun encodeTeams(teams: Map<String, List<String>>): String = json.encodeToString(teams)

    fun decodeTeams(raw: String?): Map<String, List<String>> =
        if (raw.isNullOrBlank()) emptyMap()
        else try {
            json.decodeFromString<Map<String, List<String>>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
}

/**
 * File-backed agent/team store WITH observable state.
 *
 * History note: this repository shipped as a fire-and-forget JSON store — the data layer and
 * the send-path relay (MessageGenerationController.runAgentRelay) were wired, but no UI was
 * ever connected, so "multi-agent" was invisible dead weight. This version adds StateFlows so
 * the settings UI can list/edit agents and pick a per-conversation team.
 *
 * IMPORTANT: instances are intentionally cheap and there may be several (the send path owns
 * one, the settings page another). The JSON FILES are the source of truth: every read goes
 * through loadAgents()/loadTeams() which re-read from disk and sync the flows, so writes from
 * one instance are always visible to the others.
 */
class AgentRepository(context: Context) {

    private val agentsFile = File(context.filesDir, "agora_agents.json")
    private val teamsFile = File(context.filesDir, "agora_agent_teams.json")

    private val json = Json { ignoreUnknownKeys = true }

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private val _teams = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val teams: StateFlow<Map<String, List<String>>> = _teams.asStateFlow()

    init {
        _agents.value = AgentCodec.decodeAgents(agentsFile.readTextSafe())
        _teams.value = AgentCodec.decodeTeams(teamsFile.readTextSafe())
    }

    @Synchronized
    fun loadAgents(): List<Agent> {
        val decoded = AgentCodec.decodeAgents(agentsFile.readTextSafe())
        if (decoded != _agents.value) _agents.value = decoded
        return decoded
    }

    @Synchronized
    fun saveAgents(agents: List<Agent>) {
        agentsFile.writeText(AgentCodec.encodeAgents(agents))
        _agents.value = agents.toList()
    }

    @Synchronized
    fun loadTeams(): Map<String, List<String>> {
        val decoded = AgentCodec.decodeTeams(teamsFile.readTextSafe())
        if (decoded != _teams.value) _teams.value = decoded
        return decoded
    }

    @Synchronized
    fun saveTeams(teams: Map<String, List<String>>) {
        teamsFile.writeText(AgentCodec.encodeTeams(teams))
        _teams.value = teams.toMap()
    }

    /** Re-reads from disk first: multiple instances share the same files, not each other. */
    @Synchronized
    fun teamFor(conversationId: String): List<Agent> {
        val ids = loadTeams()[conversationId] ?: return emptyList()
        val byId = loadAgents().associateBy { it.id }
        return ids.mapNotNull { byId[it] }.filter { it.enabled }
    }

    @Synchronized
    fun setTeamFor(conversationId: String, agentIds: List<String>) {
        val teams = loadTeams().toMutableMap()
        if (agentIds.isEmpty()) teams.remove(conversationId) else teams[conversationId] = agentIds
        saveTeams(teams)
    }

    /** Replace one agent (update) or append it (id not present). Emits a new list. */
    @Synchronized
    fun upsertAgent(agent: Agent) {
        val current = loadAgents()
        val next = if (current.any { it.id == agent.id }) {
            current.map { if (it.id == agent.id) agent else it }
        } else {
            current + agent
        }
        saveAgents(next)
    }

    @Synchronized
    fun deleteAgent(agentId: String) {
        saveAgents(loadAgents().filterNot { it.id == agentId })
        // Drop the id from every team so a deleted agent cannot linger in a roster.
        val teams = loadTeams().mapValues { (_, ids) -> ids.filterNot { it == agentId } }
            .filterValues { it.isNotEmpty() }
        if (teams != loadTeams()) saveTeams(teams)
    }

    fun isMultiAgent(conversationId: String): Boolean = teamFor(conversationId).isNotEmpty()

    private fun File.readTextSafe(): String? =
        if (exists() && length() > 0L) readText() else null
}
