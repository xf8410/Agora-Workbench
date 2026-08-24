package com.newoether.agora.workspace

import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A branch stage is never run concurrently with another stage in the same workspace. */
enum class WorkspaceStageStatus { IDLE, QUEUED, RUNNING, SUCCESS, FAILED, SKIPPED, STOPPED }
enum class WorkspaceRunMode { ALL_IN_ORDER, TEST_ONE }

data class WorkspaceStageState(
    val status: WorkspaceStageStatus = WorkspaceStageStatus.IDLE,
    val conversationId: String? = null,
    val result: String = "",
    val error: String? = null,
)

data class WorkspacePlanState(
    val running: Boolean = false,
    val mode: WorkspaceRunMode = WorkspaceRunMode.TEST_ONE,
    val activeLaneKey: String? = null,
    val request: String = "",
    val stages: Map<String, WorkspaceStageState> = emptyMap(),
)

data class WorkspaceChatMessage(
    val id: String,
    val text: String,
    val participant: Participant,
    val status: MessageStatus,
    val timestamp: Long,
)

/**
 * Owns the workspace execution slot and two durable, lane-scoped conversations. Every user turn
 * in a lane is appended to the same deterministic conversation, so later turns retain context.
 */
class WorkspaceAgentRunner(
    private val conversations: ConversationRepository,
    private val engine: TaskExecutionEngine,
    private val scope: CoroutineScope,
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<WorkspacePlanState>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun state(workspaceId: String): StateFlow<WorkspacePlanState> = mutableState(workspaceId).asStateFlow()

    fun messages(workspaceId: String, laneKey: String): Flow<List<WorkspaceChatMessage>> =
        conversations.getMessagesForConversation(conversationId(workspaceId, laneKey), limit = 500)
            .map { rows ->
                rows.map { row ->
                    WorkspaceChatMessage(row.id, row.text, row.participant, row.status, row.timestamp)
                }
            }

    suspend fun prepareLane(workspaceId: String, lane: WorkspaceLaneConfig): String =
        ensureConversation(workspaceId, lane.id.name, lane)

    fun send(
        workspaceId: String,
        lanes: List<WorkspaceLaneConfig>,
        selectedLaneKey: String,
        request: String,
        modelId: String? = null,
    ) = schedule(workspaceId, lanes, request, WorkspaceRunMode.TEST_ONE, selectedLaneKey, modelId)

    fun runAll(
        workspaceId: String,
        lanes: List<WorkspaceLaneConfig>,
        request: String,
        modelId: String? = null,
    ) = schedule(workspaceId, lanes, request, WorkspaceRunMode.ALL_IN_ORDER, null, modelId)

    fun testOne(
        workspaceId: String,
        lanes: List<WorkspaceLaneConfig>,
        selectedLaneKey: String,
        request: String,
        modelId: String? = null,
    ) = send(workspaceId, lanes, selectedLaneKey, request, modelId)

    fun stop(workspaceId: String) {
        jobs.remove(workspaceId)?.cancel(CancellationException("用户已停止任务"))
        val state = mutableState(workspaceId)
        state.value = state.value.copy(
            running = false,
            activeLaneKey = null,
            stages = state.value.stages.mapValues { (_, stage) ->
                if (stage.status == WorkspaceStageStatus.RUNNING || stage.status == WorkspaceStageStatus.QUEUED) {
                    stage.copy(status = WorkspaceStageStatus.STOPPED, error = null)
                } else stage
            },
        )
    }

    private fun schedule(
        workspaceId: String,
        lanes: List<WorkspaceLaneConfig>,
        request: String,
        mode: WorkspaceRunMode,
        selectedLaneKey: String?,
        modelId: String?,
    ) {
        val clean = request.trim()
        require(clean.isNotEmpty()) { "Workspace task is empty" }
        require(lanes.isNotEmpty()) { "Workspace has no branch stages" }
        if (jobs[workspaceId]?.isActive == true) return
        if (mode == WorkspaceRunMode.TEST_ONE) {
            require(lanes.any { it.id.name == selectedLaneKey }) { "Selected branch stage does not exist" }
        }

        val state = mutableState(workspaceId)
        val initial = lanes.associate { lane ->
            val key = lane.id.name
            key to WorkspaceStageState(
                status = if (mode == WorkspaceRunMode.TEST_ONE && key != selectedLaneKey) WorkspaceStageStatus.SKIPPED
                else WorkspaceStageStatus.QUEUED,
                conversationId = conversationId(workspaceId, key),
            )
        }
        state.value = WorkspacePlanState(running = true, mode = mode, request = clean, stages = initial)

        val job = scope.launch {
            var previousResult = ""
            val selected = if (mode == WorkspaceRunMode.TEST_ONE) lanes.filter { it.id.name == selectedLaneKey } else lanes

            for (lane in selected) {
                val laneKey = lane.id.name
                val current = state.value.stages.getValue(laneKey)
                val conversationId = ensureConversation(workspaceId, laneKey, lane)
                updateStage(state, laneKey, current.copy(
                    status = WorkspaceStageStatus.RUNNING,
                    conversationId = conversationId,
                    result = "",
                    error = null,
                ), active = laneKey)

                val stageRequest = buildString {
                    append(clean)
                    if (previousResult.isNotBlank()) {
                        append("\n\nPrevious branch stage completed with this hand-off:\n")
                        append(previousResult.take(12_000))
                    }
                }
                when (val result = engine.runOnce(
                    conversationId = conversationId,
                    userText = stageRequest,
                    modelId = modelId,
                    systemPromptOverride = workspaceSystemPrompt(workspaceId, laneKey, lane, mode),
                    foregroundServiceManagedExternally = false,
                    githubWorkspaceMode = true,
                    githubAllowedRepositories = setOf(lane.forkRepository, lane.upstreamRepository),
                )) {
                    is TaskExecutionEngine.Result.Success -> {
                        val visibleResult = WorkspaceOutputPolicy.sanitize(result.text)
                        previousResult = visibleResult
                        updateStage(state, laneKey, state.value.stages.getValue(laneKey).copy(
                            status = WorkspaceStageStatus.SUCCESS, result = visibleResult, error = null,
                        ), active = null)
                    }
                    is TaskExecutionEngine.Result.Failure -> {
                        updateStage(state, laneKey, state.value.stages.getValue(laneKey).copy(
                            status = WorkspaceStageStatus.FAILED, error = result.reason,
                        ), active = null)
                        if (mode == WorkspaceRunMode.ALL_IN_ORDER) {
                            state.value = state.value.copy(stages = state.value.stages.mapValues { (_, stage) ->
                                if (stage.status == WorkspaceStageStatus.QUEUED) stage.copy(
                                    status = WorkspaceStageStatus.SKIPPED,
                                    error = "Skipped because an earlier branch failed",
                                ) else stage
                            })
                        }
                        break
                    }
                }
            }
            state.value = state.value.copy(running = false, activeLaneKey = null)
        }
        jobs[workspaceId] = job
        job.invokeOnCompletion { failure ->
            jobs.remove(workspaceId, job)
            if (failure != null) {
                val cancelled = failure is CancellationException
                state.value = state.value.copy(
                    running = false,
                    activeLaneKey = null,
                    stages = state.value.stages.mapValues { (_, stage) ->
                        if (stage.status == WorkspaceStageStatus.RUNNING || stage.status == WorkspaceStageStatus.QUEUED) {
                            stage.copy(
                                status = if (cancelled) WorkspaceStageStatus.STOPPED else WorkspaceStageStatus.FAILED,
                                error = if (cancelled) null else (failure.message ?: "任务执行失败"),
                            )
                        } else stage
                    },
                )
            }
        }
    }

    private fun updateStage(
        state: MutableStateFlow<WorkspacePlanState>,
        laneKey: String,
        stage: WorkspaceStageState,
        active: String?,
    ) {
        state.value = state.value.copy(activeLaneKey = active, stages = state.value.stages + (laneKey to stage))
    }

    private fun conversationId(workspaceId: String, laneKey: String): String =
        "workspace_${workspaceId}_${laneKey.lowercase()}"

    private suspend fun ensureConversation(
        workspaceId: String,
        laneKey: String,
        config: WorkspaceLaneConfig,
    ): String {
        val id = conversationId(workspaceId, laneKey)
        if (conversations.getConversation(id) == null) {
            conversations.upsertConversation(ChatEntity(
                id = id,
                title = "${config.title} · ${config.forkRepository}",
                origin = "workspace:$workspaceId:$laneKey",
                graduated = false,
            ))
        }
        return id
    }

    private fun mutableState(workspaceId: String) =
        states.getOrPut(workspaceId) { MutableStateFlow(WorkspacePlanState()) }

    private fun workspaceSystemPrompt(
        workspaceId: String,
        laneKey: String,
        config: WorkspaceLaneConfig,
        mode: WorkspaceRunMode,
    ): String = """
        You are in a persistent GitHub workspace conversation.
        Workspace: $workspaceId
        Lane: $laneKey (${config.title})
        Run mode: $mode
        Writable fork: ${config.forkRepository}
        Persistent lane branch: ${config.forkBaseBranch}
        Upstream repository: ${config.upstreamRepository}
        Upstream target: ${config.upstreamBaseBranch}
        Squash required: ${config.squashRequired}

        Continue from this lane's existing conversation and repository state. Never act on the other
        lane. Reuse the existing lane branch and existing pull request when the user asks to update
        them; do not create a duplicate PR. Temporary implementation commits may use workbench/*,
        but the validated result belongs on the configured persistent lane branch. Never write
        directly to upstream main/master. Re-read exact refs before mutations. Remote mutations
        require foreground user confirmation.
    """.trimIndent()
}
