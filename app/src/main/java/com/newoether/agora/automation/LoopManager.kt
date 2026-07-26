package com.newoether.agora.automation

import android.content.Context
import com.newoether.agora.data.local.LoopEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.service.LoopWorker
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped owner of persistent per-conversation Loops.
 *
 * [AutomationScheduler] observes the Room loop flow and owns alarm scheduling. A [LoopWorker]
 * executes exactly one fired cycle, then this manager advances persistent state; that Room write
 * causes the scheduler to arm the next alarm. Configuration changes increment
 * [LoopEntity.revision], so a turn already in flight can never overwrite a stop/restart that
 * happened while the model was running.
 */
class LoopManager(
    private val taskRepository: TaskRepository,
    private val conversationRepository: ConversationRepository,
    private val engine: TaskExecutionEngine,
    private val cancelWork: (String) -> Unit = {},
    private val cancelAlarm: suspend (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    executionCoordinator: ConversationExecutionCoordinator? = null,
) {
    /** Production convenience constructor; the primary constructor stays fully JVM-testable. */
    constructor(
        context: Context,
        taskRepository: TaskRepository,
        conversationRepository: ConversationRepository,
        engine: TaskExecutionEngine,
        executionCoordinator: ConversationExecutionCoordinator,
    ) : this(
        taskRepository = taskRepository,
        conversationRepository = conversationRepository,
        engine = engine,
        cancelWork = { conversationId -> LoopWorker.cancel(context, conversationId) },
        executionCoordinator = executionCoordinator,
    )

    sealed interface StartResult {
        data class Started(val loop: LoopEntity) : StartResult
        data class Conflict(val existing: LoopEntity) : StartResult
        data class Invalid(val reason: String) : StartResult
        data object ConversationMissing : StartResult
    }

    sealed interface StopResult {
        data object Stopped : StopResult
        data object AlreadyStopped : StopResult
        data object NotFound : StopResult
    }

    sealed interface ExecutionResult {
        data object NotFound : ExecutionResult
        data object Inactive : ExecutionResult
        data class NotDue(val nextFireAt: Long) : ExecutionResult
        data class Superseded(val current: LoopEntity?) : ExecutionResult
        data class Finished(
            val generationResult: TaskExecutionEngine.Result,
            val loop: LoopEntity,
        ) : ExecutionResult
    }

    private val stateMutex = Mutex()
    // Use the SHARED coordinator when injected (production), so a Loop fire on conversation X
    // is serialized against a user Send on X. Falls back to a private instance for JVM tests
    // that don't supply one (S4: same-conversation race fix).
    private val executionCoordinator = executionCoordinator ?: ConversationExecutionCoordinator()

    private val _runningConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val runningConversationIds = _runningConversationIds.asStateFlow()

    fun getLoop(conversationId: String): Flow<LoopEntity?> =
        taskRepository.getLoop(conversationId)

    fun loopForConversation(conversationId: String): Flow<LoopEntity?> = getLoop(conversationId)

    /** Cancels every queued/running occurrence and removes its alarm before graph import. */
    suspend fun cancelAllExecutionsForImport() {
        taskRepository.getAllLoopsSnapshot().forEach { loop ->
            cancelWorkBestEffort(loop.conversationId)
            runCatching { cancelAlarm(loop.conversationId) }
                .onFailure {
                    DebugLog.w(
                        "LoopManager",
                        "Failed to cancel alarm for ${loop.conversationId}",
                        it,
                    )
                }
        }
    }

    suspend fun startLoop(
        conversationId: String,
        intervalMs: Long,
        prompt: String? = null,
        maxCycles: Int = LoopPolicy.DEFAULT_MAX_CYCLES,
    ): StartResult {
        if (conversationId.isBlank()) return StartResult.Invalid("conversationId must not be blank")
        LoopPolicy.validate(intervalMs, maxCycles)?.let { return StartResult.Invalid(it) }

        return stateMutex.withLock {
            if (conversationRepository.getConversation(conversationId) == null) {
                return@withLock StartResult.ConversationMissing
            }
            val existing = taskRepository.getLoop(conversationId).first()
            if (existing?.active == true) return@withLock StartResult.Conflict(existing)

            val revision = if (existing == null) 0L else LoopPolicy.nextRevision(existing.revision)
            val loop = LoopEntity(
                conversationId = conversationId,
                intervalMs = intervalMs,
                prompt = LoopPolicy.normalizePrompt(prompt),
                nextFireAt = LoopPolicy.nextFireAt(clock(), intervalMs),
                cycleCount = 0,
                maxCycles = maxCycles,
                active = true,
                revision = revision,
            )
            taskRepository.upsertLoop(loop)
            StartResult.Started(loop)
        }
    }

    suspend fun stopLoop(conversationId: String): StopResult = stateMutex.withLock {
        val existing = taskRepository.getLoop(conversationId).first()
            ?: return@withLock StopResult.NotFound
        if (!existing.active) {
            // The final cycle is claimed by persisting active=false before generation starts.
            // It can therefore still own a live LoopWorker even though its durable state already
            // looks stopped. Always cancel tagged work so Stop/Delete remains effective then too.
            cancelWorkBestEffort(conversationId)
            return@withLock StopResult.AlreadyStopped
        }

        taskRepository.upsertLoop(
            existing.copy(
                active = false,
                revision = LoopPolicy.nextRevision(existing.revision),
            )
        )
        // Cancellation is best effort; the revision change is the correctness boundary.
        cancelWorkBestEffort(conversationId)
        StopResult.Stopped
    }

    private fun cancelWorkBestEffort(conversationId: String) {
        runCatching { cancelWork(conversationId) }
            .onFailure { DebugLog.w("LoopManager", "Failed to cancel work for $conversationId", it) }
    }

    /**
     * Executes at most one due cycle. Normal model failures are returned as [Finished] and count
     * as a cycle; only infrastructure exceptions escape for [com.newoether.agora.service.LoopWorker]
     * to retry. This avoids replaying a model turn that may already have been persisted.
     */
    suspend fun executeByConversationId(
        conversationId: String,
        expectedFireAt: Long = 0L,
    ): ExecutionResult =
        executionCoordinator.withAutomationConversationLock(conversationId) conversationLock@ {
            val snapshot = stateMutex.withLock {
                val loop = taskRepository.getLoop(conversationId).first()
                    ?: return@withLock null
                if (!loop.active) return@withLock loop

                val maxCycles = loop.maxCycles ?: LoopPolicy.DEFAULT_MAX_CYCLES
                if (LoopPolicy.validate(loop.intervalMs, maxCycles) != null || loop.cycleCount >= maxCycles) {
                    val inactive = loop.copy(
                        active = false,
                        maxCycles = maxCycles,
                        nextFireAt = 0L,
                        revision = LoopPolicy.nextRevision(loop.revision),
                    )
                    taskRepository.upsertLoop(inactive)
                    return@withLock inactive
                }
                if (loop.maxCycles == null) {
                    loop.copy(maxCycles = maxCycles).also { taskRepository.upsertLoop(it) }
                } else {
                    loop
                }
            }

            if (snapshot == null) return@conversationLock ExecutionResult.NotFound
            if (!snapshot.active) return@conversationLock ExecutionResult.Inactive
            if (expectedFireAt > 0L && snapshot.nextFireAt != expectedFireAt) {
                return@conversationLock ExecutionResult.Superseded(snapshot)
            }

            val now = clock()
            if (snapshot.nextFireAt > now) {
                return@conversationLock ExecutionResult.NotDue(snapshot.nextFireAt)
            }

            val conversation = conversationRepository.getConversation(conversationId)
            if (conversation == null) {
                stateMutex.withLock { taskRepository.deleteLoop(conversationId) }
                return@conversationLock ExecutionResult.NotFound
            }

            // Persistently claim this cycle *before* any model/tool side effect. If the process
            // dies after this write, a WorkManager retry sees a different nextFireAt/cycleCount
            // and cannot replay the same turn. The next alarm is provisionally scheduled now;
            // successful completion below moves it to one full interval after completion.
            val claimed = stateMutex.withLock {
                val latest = taskRepository.getLoop(conversationId).first()
                if (
                    latest == null || !latest.active || latest.revision != snapshot.revision ||
                    latest.cycleCount != snapshot.cycleCount || latest.nextFireAt != snapshot.nextFireAt
                ) {
                    return@withLock null
                }
                val maxCycles = latest.maxCycles ?: LoopPolicy.DEFAULT_MAX_CYCLES
                val nextCount = latest.cycleCount + 1
                val remainsActive = nextCount < maxCycles
                latest.copy(
                    maxCycles = maxCycles,
                    cycleCount = nextCount,
                    active = remainsActive,
                    nextFireAt = if (remainsActive) {
                        LoopPolicy.nextFireAt(clock(), latest.intervalMs)
                    } else {
                        0L
                    },
                ).also { taskRepository.upsertLoop(it) }
            } ?: return@conversationLock ExecutionResult.Superseded(
                taskRepository.getLoop(conversationId).first()
            )

            _runningConversationIds.update { it + conversationId }
            val generationResult = try {
                engine.runOnceWithConversationLockHeld(
                    conversationId = conversationId,
                    userText = LoopPolicy.promptForExecution(claimed.prompt),
                    modelId = conversation.modelId,
                    systemPromptOverride = null,
                    foregroundServiceManagedExternally = true,
                    precondition = {
                        val latest = taskRepository.getLoop(conversationId).first()
                        latest != null && latest.revision == claimed.revision &&
                            latest.cycleCount == claimed.cycleCount
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                _runningConversationIds.update { it - conversationId }
            }

            val updated = stateMutex.withLock {
                val latest = taskRepository.getLoop(conversationId).first()
                if (
                    latest == null || latest.revision != claimed.revision ||
                    latest.cycleCount != claimed.cycleCount
                ) {
                    return@withLock null
                }

                val next = if (latest.active) {
                    latest.copy(nextFireAt = LoopPolicy.nextFireAt(clock(), latest.intervalMs))
                } else {
                    latest
                }
                if (next != latest) taskRepository.upsertLoop(next)
                next
            }

            if (updated == null) {
                ExecutionResult.Superseded(taskRepository.getLoop(conversationId).first())
            } else {
                ExecutionResult.Finished(generationResult, updated)
            }
        }

    /**
     * Ensures a one-shot Loop alarm is never lost when infrastructure fails before a cycle can be
     * claimed. Called only after WorkManager exhausts its bounded retry budget.
     */
    suspend fun deferAfterInfrastructureFailure(conversationId: String): Boolean =
        stateMutex.withLock {
            val latest = taskRepository.getLoop(conversationId).first()
                ?: return@withLock false
            if (!latest.active) return@withLock false
            val maxCycles = latest.maxCycles ?: LoopPolicy.DEFAULT_MAX_CYCLES
            if (LoopPolicy.validate(latest.intervalMs, maxCycles) != null) {
                taskRepository.upsertLoop(
                    latest.copy(
                        active = false,
                        maxCycles = maxCycles,
                        nextFireAt = 0L,
                        revision = LoopPolicy.nextRevision(latest.revision),
                    )
                )
                return@withLock false
            }
            taskRepository.upsertLoop(
                latest.copy(
                    maxCycles = maxCycles,
                    nextFireAt = LoopPolicy.nextFireAt(clock(), latest.intervalMs),
                )
            )
            true
        }
}
