package com.newoether.agora.workspace

import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Runtime state for one independently executable workspace lane. */
data class WorkspaceAgentState(
    val running: Boolean = false,
    val conversationId: String? = null,
    val lastRequest: String = "",
    val lastResult: String = "",
    val error: String? = null,
)

/**
 * Runs workspace requests through the same agentic GenerationManager as chat, including GitHub
 * read/write, PR, Actions and raw-log tools. Each lane owns a separate hidden conversation and job.
 */
class WorkspaceAgentRunner(
    private val conversations: ConversationRepository,
    private val engine: TaskExecutionEngine,
    private val scope: CoroutineScope,
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<WorkspaceAgentState>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun state(workspaceId: String, laneKey: String): StateFlow<WorkspaceAgentState> =
        mutableState(workspaceId, laneKey).asStateFlow()

    fun run(
        workspaceId: String,
        laneKey: String,
        config: WorkspaceLaneConfig,
        request: String,
        modelId: String? = null,
    ) {
        val clean = request.trim()
        require(clean.isNotEmpty()) { "Workspace task is empty" }
        val key = key(workspaceId, laneKey)
        if (jobs[key]?.isActive == true) return
        val state = mutableState(workspaceId, laneKey)
        val job = scope.launch {
            val conversationId = ensureConversation(workspaceId, laneKey, config, state.value.conversationId)
            state.value = WorkspaceAgentState(
                running = true,
                conversationId = conversationId,
                lastRequest = clean,
            )
            val systemPrompt = workspaceSystemPrompt(workspaceId, laneKey, config)
            when (val result = engine.runOnce(
                conversationId = conversationId,
                userText = clean,
                modelId = modelId,
                systemPromptOverride = systemPrompt,
                foregroundServiceManagedExternally = false,
            )) {
                is TaskExecutionEngine.Result.Success -> state.value = state.value.copy(
                    running = false,
                    lastResult = result.text,
                    error = null,
                )
                is TaskExecutionEngine.Result.Failure -> state.value = state.value.copy(
                    running = false,
                    error = result.reason,
                )
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { failure ->
            jobs.remove(key, job)
            if (failure != null) {
                state.value = state.value.copy(
                    running = false,
                    error = failure.message ?: "Workspace task stopped",
                )
            }
        }
    }

    fun stop(workspaceId: String, laneKey: String) {
        jobs.remove(key(workspaceId, laneKey))?.cancel()
    }

    private suspend fun ensureConversation(
        workspaceId: String,
        laneKey: String,
        config: WorkspaceLaneConfig,
        currentId: String?,
    ): String {
        if (currentId != null && conversations.getConversation(currentId) != null) return currentId
        val id = "workspace_${UUID.randomUUID()}"
        conversations.upsertConversation(
            ChatEntity(
                id = id,
                title = "${config.title} · ${config.forkRepository}",
                origin = "workspace:$workspaceId:$laneKey",
                graduated = false,
            )
        )
        return id
    }

    private fun mutableState(workspaceId: String, laneKey: String): MutableStateFlow<WorkspaceAgentState> =
        states.getOrPut(key(workspaceId, laneKey)) { MutableStateFlow(WorkspaceAgentState()) }

    private fun key(workspaceId: String, laneKey: String) = "$workspaceId::$laneKey"

    private fun workspaceSystemPrompt(
        workspaceId: String,
        laneKey: String,
        config: WorkspaceLaneConfig,
    ): String = """
        You are executing a task inside an Agora GitHub workspace lane.
        Workspace: $workspaceId
        Lane: $laneKey (${config.title})
        Writable fork: ${config.forkRepository}
        Fork baseline: ${config.forkBaseBranch}
        Upstream repository: ${config.upstreamRepository}
        Upstream target: ${config.upstreamBaseBranch}
        Squash required: ${config.squashRequired}

        You have the built-in GitHub tools, including repository reads, branches, commits, file
        writes on workbench/* branches, Actions runs/logs, fork creation, baseline synchronization,
        and cross-fork pull requests. Keep all mutations inside this lane's configured repositories.
        Never treat another lane as the current branch. Re-read the exact ref SHA before every
        mutation. Remote mutations require the user's confirmation. If squash is required, never
        merge experimental history directly into the release baseline.
    """.trimIndent()
}
