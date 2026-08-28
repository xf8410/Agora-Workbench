package com.newoether.agora.data

import android.content.Context
import com.newoether.agora.model.Agent
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

class AgentRepository(context: Context) {

    private val agentsFile = File(context.filesDir, "agora_agents.json")
    private val teamsFile = File(context.filesDir, "agora_agent_teams.json")

    @Synchronized
    fun loadAgents(): List<Agent> = AgentCodec.decodeAgents(agentsFile.readTextSafe())

    @Synchronized
    fun saveAgents(agents: List<Agent>) {
        agentsFile.writeText(AgentCodec.encodeAgents(agents))
    }

    @Synchronized
    fun loadTeams(): Map<String, List<String>> = AgentCodec.decodeTeams(teamsFile.readTextSafe())

    @Synchronized
    fun saveTeams(teams: Map<String, List<String>>) {
        teamsFile.writeText(AgentCodec.encodeTeams(teams))
    }

    fun teamFor(conversationId: String): List<Agent> {
        val ids = loadTeams()[conversationId] ?: return emptyList()
        val byId = loadAgents().associateBy { it.id }
        return ids.mapNotNull { byId[it] }.filter { it.enabled }
    }

    fun setTeamFor(conversationId: String, agentIds: List<String>) {
        val teams = loadTeams().toMutableMap()
        if (agentIds.isEmpty()) teams.remove(conversationId) else teams[conversationId] = agentIds
        saveTeams(teams)
    }

    fun isMultiAgent(conversationId: String): Boolean = teamFor(conversationId).isNotEmpty()

    private fun File.readTextSafe(): String? =
        if (exists() && length() > 0L) readText() else null
}
