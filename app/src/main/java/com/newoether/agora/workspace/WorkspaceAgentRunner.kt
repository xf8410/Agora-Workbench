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
    val mode: WorkspaceRunMode = WorkspaceRunMode.ALL_IN_ORDER,
    val activeLaneKey: String? = null,
    val request: String = "",
    val stages: Map<String, WorkspaceStageState> = emptyMap(),
)

/**
 * One scheduler owns one workspace execution slot. Normal runs execute lanes in list order and
 * stop on the first failure. Test mode executes exactly one selected lane and marks all others
 * SKIPPED. Intermediate commits and CI must remain on workbench/* branches.
 */
class WorkspaceAgentRunner(
    private val conversations: ConversationRepository,
    private val engine: TaskExecutionEngine,
    private val scope: CoroutineScope,
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<WorkspacePlanState>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun state(workspaceId: String): StateFlow<WorkspacePlanState> =
        mutableState(workspaceId).asStateFlow()

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
    ) = schedule(workspaceId, lanes, request, WorkspaceRunMode.TEST_ONE, selectedLaneKey, modelId)

    fun stop(workspaceId: String) {
        jobs.remove(workspaceId)?.cancel()
        val state = mutableState(workspaceId)
        state.value = state.value.copy(
            running = false,
            activeLaneKey = null,
            stages = state.value.stages.mapValues { (_, stage) ->
                if (stage.status == WorkspaceStageStatus.RUNNING || stage.status == WorkspaceStageStatus.QUEUED) {
                    stage.copy(status = WorkspaceStageStatus.STOPPED)
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
                status = if (mode == WorkspaceRunMode.TEST_ONE && key != selectedLaneKey) {
                    WorkspaceStageStatus.SKIPPED
                } else WorkspaceStageStatus.QUEUED,
                conversationId = state.value.stages[key]?.conversationId,
            )
        }
        state.value = WorkspacePlanState(
            running = true,
            mode = mode,
            request = clean,
            stages = initial,
        )

        val job = scope.launch {
            var previousResult = ""
            val selected = if (mode == WorkspaceRunMode.TEST_ONE) {
                lanes.filter { it.id.name == selectedLaneKey }
            } else lanes

            for (lane in selected) {
                val laneKey = lane.id.name
                val current = state.value.stages.getValue(laneKey)
                val conversationId = ensureConversation(workspaceId, laneKey, lane, current.conversationId)
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
                        previousResult = result.text
                        updateStage(state, laneKey, state.value.stages.getValue(laneKey).copy(
                            status = WorkspaceStageStatus.SUCCESS,
                            result = result.text,
                            error = null,
                        ), active = null)
                    }
                    is TaskExecutionEngine.Result.Failure -> {
                        updateStage(state, laneKey, state.value.stages.getValue(laneKey).copy(
                            status = WorkspaceStageStatus.FAILED,
                            error = result.reason,
                        ), active = null)
                        if (mode == WorkspaceRunMode.ALL_IN_ORDER) {
                            state.value = state.value.copy(stages = state.value.stages.mapValues { (_, stage) ->
                                if (stage.status == WorkspaceStageStatus.QUEUED) {
                                    stage.copy(status = WorkspaceStageStatus.SKIPPED, error = "Skipped because an earlier branch failed")
                                } else stage
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
                state.value = state.value.copy(
                    running = false,
                    activeLaneKey = null,
                    stages = state.value.stages.mapValues { (_, stage) ->
                        if (stage.status == WorkspaceStageStatus.RUNNING || stage.status == WorkspaceStageStatus.QUEUED) {
                            stage.copy(status = WorkspaceStageStatus.STOPPED, error = failure.message)
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
        state.value = state.value.copy(
            activeLaneKey = active,
            stages = state.value.stages + (laneKey to stage),
        )
    }

    private suspend fun ensureConversation(
        workspaceId: String,
        laneKey: String,
        config: WorkspaceLaneConfig,
        currentId: String?,
    ): String {
        if (currentId != null && conversations.getConversation(currentId) != null) return currentId
        val id = "workspace_${UUID.randomUUID()}"
        conversations.upsertConversation(ChatEntity(
            id = id,
            title = "${config.title} · ${config.forkRepository}",
            origin = "workspace:$workspaceId:$laneKey",
            graduated = false,
        ))
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
        You are executing one ordered branch stage selected by the Agora workspace scheduler.
        Workspace: $workspaceId
        Stage: $laneKey (${config.title})
        Run mode: $mode
        Writable fork: ${config.forkRepository}
        Baseline branch (read/base only): ${config.forkBaseBranch}
        Upstream repository: ${config.upstreamRepository}
        Upstream target: ${config.upstreamBaseBranch}
        Squash required: ${config.squashRequired}

        This stage is the only active stage. Do not start or simulate another stage. Put every
        intermediate commit and every CI run on a workbench/* branch. Never dispatch development
        tests on main or master, and never write directly to main/master or to the configured
        baseline. Main/master may only be the final pull-request target after successful validation.
        Re-read exact refs before mutations. If this stage fails, report the failure; the scheduler
        will skip later stages. Remote mutations require foreground user confirmation.
    """.trimIndent()
}
