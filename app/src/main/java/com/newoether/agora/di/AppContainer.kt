package com.newoether.agora.di

import android.app.Application
import android.content.Context
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.local.ChatDatabase
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.data.repository.TaskRepository
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.AutomationScheduler
import com.newoether.agora.automation.AutomationExecutionGate
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.TaskExecutionEngine
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.tool.AutomationToolProvider
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.service.TaskWorker
import com.newoether.agora.viewmodel.ChatViewModel
import com.newoether.agora.viewmodel.ChatViewModelFactory
import com.newoether.agora.viewmodel.ProviderRegistry
import kotlinx.coroutines.flow.first

/**
 * Centralized dependency container (manual DI).
 *
 * Replaces the ad-hoc dependency creation previously spread across
 * MainActivity (ChatDatabase.build, ChatViewModelFactory instantiation).
 * All shared dependencies are created once and reused.
 *
 * This is a stepping stone toward a full DI framework (Hilt/Koin);
 * for a single-module project it provides sufficient decoupling and
 * testability without annotation processing overhead.
 */
class AppContainer(private val appContext: Context) {
    private val application = appContext.applicationContext as Application

    /** App-lifetime scope that backs the shared settings StateFlows. */
    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    // ── Data Layer ────────────────────────────────────────────

    val settingsManager: SettingsManager by lazy { SettingsManager(appContext) }
    val memoryManager: MemoryManager by lazy { MemoryManager(appContext) }
    val database: ChatDatabase by lazy { ChatDatabase.build(appContext) }
    val chatDao: ChatDao by lazy { database.chatDao() }

    // ── Repositories ──────────────────────────────────────────

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(chatDao)
    }
    val taskRepository: TaskRepository by lazy {
        TaskRepository(chatDao)
    }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsManager, appScope)
    }

    // ── Generation singletons (process-scoped) ────────────────
    // Shared by both the foreground ChatViewModel and background task execution.
    // [localProvider] must be unique per process (owns the on-device llama engine +
    // LlamaEngine.modelMutex); [providerRegistry] holds the live provider map the
    // generation pipeline reads and runs the long-lived credential/model sync jobs.

    val localProvider: LocalProvider by lazy { LocalProvider(appContext, settingsRepository) }

    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry(settingsRepository, localProvider, appScope).also { it.launchSyncJobs() }
    }

    /** Serializes every foreground/background generation touching the same conversation. */
    val conversationExecutionCoordinator: ConversationExecutionCoordinator by lazy {
        ConversationExecutionCoordinator()
    }

    /** Lets native import quiesce Task/Loop generation without serializing ordinary executions. */
    val automationExecutionGate: AutomationExecutionGate by lazy { AutomationExecutionGate() }

    // ── Sandbox (flavor-specific) ─────────────────────────────

    val sandboxManagerFactory: SandboxManagerFactory? by lazy {
        try {
            // fdroid flavor provides FdroidSandboxManagerFactory
            Class.forName("com.newoether.agora.sandbox.FdroidSandboxManagerFactory")
                .getDeclaredConstructor(android.content.Context::class.java)
                .newInstance(appContext) as SandboxManagerFactory
        } catch (_: ClassNotFoundException) {
            // play flavor provides PlaySandboxManagerFactory
            try {
                Class.forName("com.newoether.agora.sandbox.PlaySandboxManagerFactory")
                    .getDeclaredConstructor()
                    .newInstance() as SandboxManagerFactory
            } catch (_: ClassNotFoundException) {
                null
            } catch (e: Exception) {
                // Class exists but failed to construct — this is a real error, not a flavor miss.
                com.newoether.agora.util.DebugLog.e("AppContainer", "PlaySandboxManagerFactory init failed", e)
                null
            }
        } catch (e: Exception) {
            // FdroidSandboxManagerFactory exists but failed to construct.
            com.newoether.agora.util.DebugLog.e("AppContainer", "FdroidSandboxManagerFactory init failed", e)
            null
        }
    }

    // ── Headless task execution (process-scoped) ──────────────
    // Drives a full generation with no ViewModel/UI, reusing the shared generation
    // singletons above. Background Task/Loop runners call its runOnce(...).

    val taskExecutionEngine: TaskExecutionEngine by lazy {
        TaskExecutionEngine(
            application = application,
            appContext = appContext,
            convRepo = conversationRepository,
            settings = settingsRepository,
            memoryManager = memoryManager,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            sandboxFactory = sandboxManagerFactory,
            appScope = appScope,
            executionCoordinator = conversationExecutionCoordinator,
            automationExecutionGate = automationExecutionGate,
        )
    }

    val taskManager: TaskManager by lazy {
        TaskManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            scope = appScope,
            cancelScheduledExecution = { taskId ->
                TaskWorker.cancel(appContext, taskId)
                automationScheduler.cancelTask(taskId)
            },
            cancelConversationLoop = { conversationId ->
                loopManager.stopLoop(conversationId)
            },
            refreshScheduling = { automationScheduler.refresh() },
            conversationExecutionCoordinator = conversationExecutionCoordinator,
        )
    }

    val loopManager: LoopManager by lazy {
        LoopManager(
            taskRepository = taskRepository,
            conversationRepository = conversationRepository,
            engine = taskExecutionEngine,
            cancelWork = { conversationId ->
                com.newoether.agora.service.LoopWorker.cancel(appContext, conversationId)
            },
            cancelAlarm = { conversationId -> automationScheduler.cancelLoop(conversationId) },
            executionCoordinator = conversationExecutionCoordinator,
        )
    }

    /** Foreground-only provider: headless automation cannot recursively create automation. */
    val automationToolProvider: AutomationToolProvider by lazy {
        AutomationToolProvider(taskManager, loopManager) {
            settingsManager.automationToolsEnabled.first()
        }
    }

    val automationScheduler: AutomationScheduler by lazy {
        AutomationScheduler(appContext, taskRepository, settingsRepository, appScope).also { it.start() }
    }

    // ── Auto Backup ───────────────────────────────────────────

    val autoBackupManager: AutoBackupManager by lazy {
        AutoBackupManager(appContext, settingsManager, chatDao, memoryManager)
    }

    // ── ViewModel Factory ─────────────────────────────────────

    fun chatViewModelFactory(): ChatViewModelFactory =
        ChatViewModelFactory(
            application, chatDao, settingsManager, memoryManager, appContext, sandboxManagerFactory,
            autoBackupManager, conversationRepository, settingsRepository, localProvider, providerRegistry,
            taskManager, loopManager, automationToolProvider, conversationExecutionCoordinator,
            automationExecutionGate
        )
}
