package com.newoether.agora.automation

import android.app.Application
import android.content.Context
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.ConversationUiState
import com.newoether.agora.viewmodel.GenerationCallbacks
import com.newoether.agora.viewmodel.GenerationManager
import com.newoether.agora.viewmodel.GenerationRequestBuilder
import com.newoether.agora.viewmodel.ProviderRegistry
import com.newoether.agora.viewmodel.RagManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Headless single-shot generation engine (process-scoped).
 *
 * Phase 0b skeleton: drives one complete generation (including the agentic tool
 * loop) for a conversation WITHOUT any ViewModel / Compose state, by reusing the
 * exact same [GenerationManager] pipeline the foreground path runs. Background
 * Task/Loop runners call [runOnce]; the foreground [com.newoether.agora.viewmodel.ChatViewModel]
 * keeps its own driving path for now — the full ViewModel→engine delegation is a
 * later phase (see `.claude/AUTOMATION_DESIGN.md` §3).
 *
 * Collaborators are the process-scoped singletons from `AppContainer`, so the
 * background engine shares the live provider map, the on-device llama engine, and
 * the conversation/settings repositories with the UI.
 */
class TaskExecutionEngine(
    private val application: Application,
    private val appContext: Context,
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val memoryManager: MemoryManager,
    private val providerRegistry: ProviderRegistry,
    localProvider: LocalProvider,
    sandboxFactory: SandboxManagerFactory?,
    appScope: CoroutineScope,
    private val executionCoordinator: ConversationExecutionCoordinator,
    private val automationExecutionGate: AutomationExecutionGate = AutomationExecutionGate(),
) {
    sealed interface Result {
        data class Success(val modelMessageId: String, val text: String) : Result
        data class Failure(val reason: String) : Result
    }

    /** Embedding subsystem powering RAG/semantic-search context during generation.
     *  One per engine, mirrors `ChatViewModel.ragManager` but on the app scope. */
    private val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        localProvider = localProvider,
        appContext = appContext,
        scope = appScope,
        emitSnackbar = {},
    )

    private val generationManager = GenerationManager(
        app = application,
        conversations = convRepo,
        memoryManager = memoryManager,
        providers = providerRegistry.all,
        context = appContext,
        sandboxFactory = sandboxFactory,
    ).also {
        // Remote shell remains unavailable to headless runs. Workspace GitHub mutations use
        // GitHubMutationConfirmation and therefore still fail closed unless the foreground user
        // approves the exact repository/ref/SHA dialog.
        it.onConfirmShellCommand = { _, _ -> false }
        it.onConfirmGitHubAction = { repository, summary ->
            com.newoether.agora.viewmodel.GitHubMutationConfirmation.confirm(
                "$repository\n$summary"
            )
        }
    }

    /** Headless callbacks: no UI sink, always persist (this run owns the message). */
    private val headlessCallbacks = GenerationCallbacks(
        onStreamUpdate = {},
        onLoadingChange = {},
        onStreamClear = {},
        isLatestPersist = { true },
    )

    /**
     * Injects [userText] as a new user turn at the leaf of [conversationId] and runs
     * one full generation, persisting the assistant reply. [modelId] is the prefixed
     * model id (e.g. "OpenAI:gpt-4o"); null/blank falls back to the app default model.
     *
     * [systemPromptOverride] bypasses the per-conversation / active-prompt resolution:
     * pass a task's own system prompt, or "" to run with no system prompt at all (the
     * default for task executions). Leave null to resolve the prompt the way the
     * foreground chat does (conversation's prompt id, falling back to the active one).
     */
    suspend fun runOnce(
        conversationId: String,
        userText: String,
        modelId: String? = null,
        systemPromptOverride: String? = null,
        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
        githubWorkspaceMode: Boolean = false,
        githubAllowedRepositories: Set<String> = emptySet(),
    ): Result = automationExecutionGate.withExecution {
        executionCoordinator.withAutomationConversationLock(conversationId) {
            runOnceLocked(
                conversationId = conversationId,
                userText = userText,
                modelId = modelId,
                systemPromptOverride = systemPromptOverride,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                precondition = precondition,
                githubWorkspaceMode = githubWorkspaceMode,
                githubAllowedRepositories = githubAllowedRepositories,
            )
        }
    }

    /**
     * LoopManager owns the conversation lock across its persistent cycle claim, generation, and
     * schedule update. Re-entering the non-reentrant coordinator from [runOnce] would self-deadlock,
     * so this entry point performs the same execution-gate work while trusting that outer owner.
     */
    internal suspend fun runOnceWithConversationLockHeld(
        conversationId: String,
        userText: String,
        modelId: String? = null,
        systemPromptOverride: String? = null,
        foregroundServiceManagedExternally: Boolean = false,
        precondition: suspend () -> Boolean = { true },
    ): Result = automationExecutionGate.withExecution {
        runOnceLocked(
            conversationId = conversationId,
            userText = userText,
            modelId = modelId,
            systemPromptOverride = systemPromptOverride,
            foregroundServiceManagedExternally = foregroundServiceManagedExternally,
            precondition = precondition,
            githubWorkspaceMode = false,
            githubAllowedRepositories = emptySet(),
        )
    }

    private suspend fun runOnceLocked(
        conversationId: String,
        userText: String,
        modelId: String?,
        systemPromptOverride: String?,
        foregroundServiceManagedExternally: Boolean,
        precondition: suspend () -> Boolean,
        githubWorkspaceMode: Boolean,
        githubAllowedRepositories: Set<String>,
    ): Result {
        // A Worker may construct the process from an alarm while every StateFlow still exposes
        // its eager default. Wait for the real DataStore snapshot, then synchronously materialize
        // custom providers before resolving either the model or request configuration.
        settings.awaitInitialLoad()
        providerRegistry.awaitInitialSync()
        if (!precondition()) {
            return Result.Failure("Execution cancelled")
        }
        val conversation = convRepo.getConversation(conversationId)
            ?: return Result.Failure("Conversation not found: $conversationId")
        val effectiveModelId = modelId?.takeIf { it.isNotBlank() }
            ?: conversation.modelId?.takeIf { it.isNotBlank() }
            ?: settings.selectedModel.value

        // Resolve the conversation leaf via the single source of truth used by the UI.
        val snapshot = convRepo.getMessagesForConversationSnapshot(conversationId)
        val path = ConversationUiState.resolvePath(
            allMessages = snapshot.map {
                ChatMessage(
                    id = it.id, parentId = it.parentId, text = it.text,
                    participant = it.participant, timestamp = it.timestamp, status = it.status,
                )
            },
            streamingMsg = null,
            selectedChildren = convRepo.restoreBranchSelections(conversationId),
        )
        val leafId = path.lastOrNull()?.id

        val now = System.currentTimeMillis()
        val userMessageId = UUID.randomUUID().toString()
        convRepo.upsertMessage(MessageEntity(
            id = userMessageId, conversationId = conversationId, parentId = leafId,
            text = userText, thoughts = null, status = MessageStatus.SUCCESS,
            participant = Participant.USER, timestamp = now,
        ))
        val modelMessageId = UUID.randomUUID().toString()
        val startTime = now + 1
        convRepo.upsertMessage(MessageEntity(
            id = modelMessageId, conversationId = conversationId, parentId = userMessageId,
            text = "", thoughts = null, status = MessageStatus.SENDING,
            participant = Participant.MODEL, timestamp = startTime,
            modelName = effectiveModelId.takeIf { it.isNotBlank() },
        ))
        convRepo.getConversation(conversationId)?.let { conv ->
            convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
        }

        return try {
            suspend fun fail(reason: String): Result.Failure {
                convRepo.upsertMessage(
                    MessageEntity(
                        id = modelMessageId,
                        conversationId = conversationId,
                        parentId = userMessageId,
                        text = reason,
                        thoughts = null,
                        status = MessageStatus.ERROR,
                        participant = Participant.MODEL,
                        timestamp = startTime,
                        modelName = effectiveModelId.takeIf { it.isNotBlank() },
                    )
                )
                convRepo.getConversation(conversationId)?.let { conv ->
                    convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
                }
                return Result.Failure(reason)
            }

            if (effectiveModelId.isBlank()) return fail("No model selected")
            val providerName = providerRegistry.providerForModel(effectiveModelId)
            // Re-resolve against on-disk settings (DataStore may not have loaded yet), with
            // the synchronous value as a fallback — same fresh-key logic the foreground uses.
            val activeKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() }
                ?: settings.resolveActiveKey(providerName) ?: ""
            if (!providerRegistry.isConfigured(providerName, activeKey)) {
                return fail("Provider not configured: $providerName")
            }

            // Build the request headlessly through the same builder the ViewModel uses,
            // so prompt resolution / config / context stay a single source of truth.
            val builder = GenerationRequestBuilder(
                settings = settings,
                convRepo = convRepo,
                memoryManager = memoryManager,
                providerRegistry = providerRegistry,
                ragManager = ragManager,
                appContext = appContext,
                currentActiveModel = MutableStateFlow(effectiveModelId),
                pendingConversationSettings = MutableStateFlow(null),
                onSnackbar = {},
            )
            val resolved = if (systemPromptOverride != null) {
                GenerationRequestBuilder.ResolvedPrompt(
                    systemPromptOverride.ifBlank { null },
                    null,
                    null,
                )
            } else {
                builder.buildEffectiveSystemPrompt(conversationId)
            }
            val effectiveSettings = builder.buildEffectiveConversationSettings(conversationId)
            val (config, baseGenCtx) = builder.buildGenerationPair(
                providerName, effectiveModelId, activeKey,
                resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
                effectiveSettings, conversationId,
            )
            val genCtx = baseGenCtx.copy(
                // Automation tools are intentionally foreground-only: a scheduled run must
                // not recursively create more tasks/loops without a user in the loop.
                automationToolsEnabled = false,
                foregroundServiceManagedExternally = foregroundServiceManagedExternally,
                githubWorkspaceMode = githubWorkspaceMode,
                githubAllowedRepositories = githubAllowedRepositories,
                // Scheduled-run chatter must never pollute the user's recent-session memory.
                autoSessionHandoff = false,
            )

            // No global slot: local model work is serialized inside LocalProvider via
            // LocalModelSerializer; remote generations run concurrently. A headless turn
            // therefore starts immediately and a Stop releases it without waiting on a
            // process-wide mutex.
            generationManager.generate(
                conversationId = conversationId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = false,
                replaceMessageId = null,
                modelName = effectiveModelId,
                config = config,
                ctx = genCtx,
                generationJob = null,
                callbacks = headlessCallbacks,
            )
            val finalMsg = convRepo.getMessagesForConversationSnapshot(conversationId)
                .find { it.id == modelMessageId }
            if (finalMsg != null && finalMsg.status == MessageStatus.SUCCESS) {
                Result.Success(modelMessageId, finalMsg.text)
            } else {
                Result.Failure(finalMsg?.text?.takeIf { it.isNotBlank() } ?: "Generation failed")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (convRepo.getConversation(conversationId) != null) {
                    // Preserve partial output from checkpoints instead of overwriting it with a
                    // bare "Execution cancelled" (same rule as GenerationManager's error path).
                    // Also carry over the payload columns — a bare upsert would wipe toolCallJson.
                    val existing = convRepo.getMessagesByIds(listOf(modelMessageId)).firstOrNull()
                    val partial = existing?.text.orEmpty()
                    convRepo.upsertMessage(
                        MessageEntity(
                            id = modelMessageId,
                            conversationId = conversationId,
                            parentId = userMessageId,
                            text = if (partial.isBlank()) "Execution cancelled"
                            else partial + "\n\n[Cancelled] Execution cancelled",
                            images = existing?.images ?: emptyList(),
                            thoughts = existing?.thoughts,
                            thoughtTitle = existing?.thoughtTitle,
                            tokenCount = existing?.tokenCount ?: 0,
                            status = MessageStatus.STOPPED,
                            participant = Participant.MODEL,
                            timestamp = startTime,
                            thoughtTimeMs = existing?.thoughtTimeMs,
                            modelName = effectiveModelId.takeIf { it.isNotBlank() },
                            toolCallJson = existing?.toolCallJson,
                        )
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            DebugLog.e("TaskExecutionEngine", "runOnce failed for conversation=$conversationId", e)
            val reason = e.localizedMessage ?: "Unexpected error"
            // Same preservation rule: never clobber checkpointed partial output or payloads.
            val existing = convRepo.getMessagesByIds(listOf(modelMessageId)).firstOrNull()
            val partial = existing?.text.orEmpty()
            convRepo.upsertMessage(
                MessageEntity(
                    id = modelMessageId,
                    conversationId = conversationId,
                    parentId = userMessageId,
                    text = if (partial.isBlank()) reason else partial + "\n\n[生成中断] " + reason,
                    images = existing?.images ?: emptyList(),
                    thoughts = existing?.thoughts,
                    thoughtTitle = existing?.thoughtTitle,
                    tokenCount = existing?.tokenCount ?: 0,
                    status = MessageStatus.ERROR,
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    thoughtTimeMs = existing?.thoughtTimeMs,
                    modelName = effectiveModelId.takeIf { it.isNotBlank() },
                    toolCallJson = existing?.toolCallJson,
                )
            )
            Result.Failure(reason)
        }
    }
}
