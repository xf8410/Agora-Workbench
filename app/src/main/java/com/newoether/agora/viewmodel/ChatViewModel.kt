package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newoether.agora.R
import com.newoether.agora.api.*
import com.newoether.agora.api.LlamaEngine
import com.newoether.agora.api.anthropic.*
import com.newoether.agora.api.gemini.*
import com.newoether.agora.api.local.*
import com.newoether.agora.api.ollama.*
import com.newoether.agora.api.openai.*
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.ClaudeChatImporter
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.DataExporter
import com.newoether.agora.data.DataImporter
import com.newoether.agora.data.EmbeddingModelConfig
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.PredefinedVariables

import com.newoether.agora.data.ShellDeviceConfig

import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.AttachmentItem
import com.newoether.agora.model.AttachmentMeta
import com.newoether.agora.model.ChatConversation
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.apiModelName
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.sandbox.SandboxManager
import com.newoether.agora.sandbox.SandboxManagerFactory
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AutoBackupWorker
import com.newoether.agora.ui.settings.ImportStrategy
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.PdfPageRenderer
import com.newoether.agora.util.SearchResultFormatter
import com.newoether.agora.util.SnackbarEvent
import com.newoether.agora.util.SshClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class ChatViewModel(
    application: Application,
    // [chatDao] and [settingsManager] are retained ONLY to pass to ImportExportManager,
    // which threads them into DataExporter/DataImporter (bulk data-layer utilities that
    // genuinely need raw DAO/DataStore). All other managers use repositories uniformly.
    private val chatDao: com.newoether.agora.data.local.ChatDao,
    private val settingsManager: com.newoether.agora.data.SettingsManager,
    val memoryManager: MemoryManager,
    private val appContext: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    // All injected via AppContainer/ChatViewModelFactory — the single construction site.
    val autoBackupManager: AutoBackupManager,
    conversationRepository: ConversationRepository,
    settingsRepository: SettingsRepository,
    // Process-scoped generation singletons, shared with background task execution.
    private val localProvider: LocalProvider,
    private val providerRegistry: ProviderRegistry,
    // App-scoped automation orchestrator (task CRUD + run-now).
    private val taskManager: com.newoether.agora.automation.TaskManager,
    private val loopManager: com.newoether.agora.automation.LoopManager,
    private val automationToolProvider: com.newoether.agora.tool.AutomationToolProvider,
    private val conversationExecutionCoordinator: com.newoether.agora.automation.ConversationExecutionCoordinator,
    private val automationExecutionGate: com.newoether.agora.automation.AutomationExecutionGate,
) : AndroidViewModel(application) {

    companion object {
        private const val INITIAL_MESSAGE_WINDOW = 24
        private const val MESSAGE_PAGE_SIZE = 24

        /** Overlay fade duration for conversation-switch transitions. */
        private const val SWITCH_OVERLAY_FADE_MS = 200L
        /** Auto-delete period tiers in hours: 7 days, 30 days, 365 days. */
        private val AUTO_DELETE_TIERS_HOURS = listOf(168, 720, 8760)
    }

    val settings: SettingsRepository = settingsRepository

    /**
     * Conversation/message persistence behind the repository layer. CRUD, cascade-delete,
     * branch-selection and stuck-message logic live in [ConversationRepository]; managers
     * receive the repository (not raw DAO) for a uniform boundary.
     */
    private val convRepo: ConversationRepository = conversationRepository

    /** Embedding subsystem: model CRUD + RAG cache + single-message indexing + key resolution. */
    val ragManager = RagManager(
        conversations = convRepo,
        settings = settings,
        localProvider = localProvider,
        appContext = appContext,
        scope = viewModelScope,
    ) { _snackbarMessage.emit(it) }

    /**
     * Data export/import orchestration (native backup + Claude + GPT formats).
     * [chatDao] and [settingsManager] are passed through to [DataExporter]/[DataImporter]
     * which need raw DAO/DataStore for bulk cross-table operations.
     */
    val importExport = ImportExportManager(
        app = getApplication(),
        conversations = convRepo,
        chatDao = chatDao,
        settingsManager = settingsManager,
        memoryManager = memoryManager,
        scope = viewModelScope,
        emitSnackbar = { _snackbarMessage.emit(it) },
        onDataChanged = { refreshDataCounts() },
        automationExecutionGate = automationExecutionGate,
        quiesceAutomation = {
            taskManager.cancelAllExecutionsForImport()
            loopManager.cancelAllExecutionsForImport()
        },
        resumeAutomationScheduling = taskManager::refreshSchedulingAfterImport,
    )

    /** Local (on-device) chat-model configuration CRUD. */
    val modelManager = ModelManager(settings, viewModelScope)

    // [providerRegistry] and [localProvider] are now constructor-injected, process-scoped
    // singletons (see AppContainer) so background task execution shares the same instances.

    /**
     * Startup jobs deferred until all StateFlow/property backing fields are
     * initialized — avoids the constructor this-escape where a Dispatchers.IO
     * coroutine accesses a field whose JVM backing field is still null.
     */
    /** Build the proxy config from settings and push it into the shared HttpClient. */
    private fun applyProxy() {
        val host = settings.proxyHost.value.trim()
        val cfg = if (settings.proxyEnabled.value && host.isNotEmpty()) {
            com.newoether.agora.api.HttpClient.ProxyConfig(
                type = if (settings.proxyType.value.equals("socks5", ignoreCase = true))
                    com.newoether.agora.api.HttpClient.ProxyType.SOCKS
                else com.newoether.agora.api.HttpClient.ProxyType.HTTP,
                host = host,
                port = settings.proxyPort.value.trim().toIntOrNull() ?: 0,
                username = settings.proxyUsername.value,
                password = settings.proxyPassword.value,
                bypass = settings.proxyBypass.value.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
            )
        } else null
        com.newoether.agora.api.HttpClient.setProxy(cfg)
    }

    private fun startInitJobs() {
        // Apply the network proxy at startup and whenever its settings change.
        viewModelScope.launch {
            val proxyFlows = listOf(
                settings.proxyEnabled.map { it.toString() },
                settings.proxyType, settings.proxyHost, settings.proxyPort,
                settings.proxyUsername, settings.proxyPassword, settings.proxyBypass
            )
            kotlinx.coroutines.flow.combine(proxyFlows) { it }.collect { applyProxy() }
        }
        // Agora Workbench is independently maintained. Never check or offer
        // upstream Agora releases, because those APKs use a different package/product.
        viewModelScope.launch(Dispatchers.IO) {
            val models = settings.getEmbeddingModels()
            val activeId = settings.getActiveEmbeddingModelId()
            val active = models.find { it.id == activeId } ?: return@launch
            val total = convRepo.getIndexableMessageCount()
            val cached = convRepo.getEmbeddingCountByModel(active.id)
            val notCached = (total - cached).coerceAtLeast(0)
            if (notCached > 0 && !ragManager.cachingProgress.value.containsKey(active.id)) {
                _snackbarMessage.emit(SnackbarEvent(
                    getApplication<Application>().getString(R.string.messages_not_cached, notCached, total),
                    getApplication<Application>().getString(R.string.cache_now)
                ) { cacheMessagesForModel(active.id) })
            }
        }
        // Clean up orphaned embeddings (messages that no longer exist)
        viewModelScope.launch(Dispatchers.IO) {
            convRepo.deleteOrphanedEmbeddings()
        }
        // Sweep orphaned PDF render files (pdf_* / pdf_preview_*) left in filesDir by a
        // process death while the page-select dialog was open. At startup nothing is
        // rendering and no dialog is open, so any pdf_*.jpg not referenced by a stored
        // message's images is junk and gets deleted.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val referenced = convRepo.getAllMessageImages()
                    .asSequence()
                    .flatMap { it.images.asSequence() }
                    .map { it.removePrefix("file://") }
                    .toHashSet()
                getApplication<Application>().filesDir.listFiles { f ->
                    f.isFile && f.name.startsWith("pdf_") && f.name.endsWith(".jpg")
                }?.forEach { f ->
                    if (f.absolutePath !in referenced) runCatching { f.delete() }
                }
            } catch (e: Exception) { DebugLog.d("ChatViewModel", "PDF thumbnail cleanup error", e) }
        }
        // ── Auto Backup ──────────────────────────────────────────
        try { AutoBackupWorker.schedule(getApplication()) } catch (_: Exception) {}
        viewModelScope.launch(Dispatchers.IO) {
            try { autoBackupManager.checkAndBackup() } catch (e: Exception) { DebugLog.e("ChatViewModel", "Auto backup check failed", e) }
        }
        // Sync local chat models into available models
        viewModelScope.launch {
            var lastLocalIds: List<String>? = null
            var lastAliases: Map<String, String>? = null
            settings.localChatModels.collect { models ->
                val localIds = models.map { "Local:${it.modelId}" }
                val currentAliases = settings.getModelAliases()
                val aliases = currentAliases.toMutableMap()
                models.forEach { aliases["Local:${it.modelId}"] = it.alias }
                if (localIds != lastLocalIds) {
                    settings.saveAvailableModels(Constants.PROVIDER_LOCAL, localIds)
                    lastLocalIds = localIds
                }
                if (aliases != lastAliases) {
                    settings.saveModelAliases(aliases)
                    lastAliases = aliases
                }
            }
        }
        // Provider map / model-list sync jobs now run on the process-scoped registry
        // (launched once in AppContainer), so they survive ViewModel recreation.
    }

    // Per-conversation generation lifecycle (IO scope, job, slot, race-free stop/persist tokens)
    // lives in [ConversationGenerationState], one per conversation via [generationRegistry].

    private val generationManager by lazy {
        GenerationManager(
            app = application,
            conversations = convRepo,
            memoryManager = memoryManager,
            providers = providerRegistry.all,
            context = appContext,
            sandboxFactory = sandboxFactory,
            additionalToolProviders = listOf(
                automationToolProvider,
                com.newoether.agora.tool.GitHubCloneToolProvider(appContext, sandboxFactory),
            ),
        ).also { gm ->
            gm.onMessagePersisted = { messageId, text ->
                if (settings.autoCacheEnabled.value && (settings.modelSearchMethod.value == Constants.SEARCH_METHOD_RAG || settings.manualSearchMethod.value == Constants.SEARCH_METHOD_RAG)) {
                    indexMessageForRag(messageId, text)
                }
            }
            gm.onConfirmShellCommand = { server, summary -> shellConfirmation.confirm(server, summary) }
        }
    }

    val sandboxManager: SandboxManager? by lazy {
        sandboxFactory?.create()
    }
    val isSandboxFlavor: Boolean = sandboxFactory?.isAvailable() == true

    override fun onCleared() {
        super.onCleared()
        sandboxManager?.close()
        generationRegistry.cancelAll()
        autoBackupManager.destroy()
    }

    fun getProviderInstance(name: String): LlmProvider = providerRegistry.getInstance(name)



    private val _scrollToMessage = MutableSharedFlow<String?>(replay = 0)
    val scrollToMessage = _scrollToMessage.asSharedFlow()

    /** One-shot: set when sendMessage creates a new conversation so the conversation-open
     *  auto-scroll skips once (the send's scroll-to-message already handles it), preventing
     *  a double scroll on the first message of a new chat. Consumed by ChatApp. */
    @Volatile
    var suppressNextOpenScroll: Boolean = false

    /** When true, draft write-backs are suppressed to prevent feedback loops while
     *  programmatically loading a stored draft into the composer field. */
    @Volatile
    var loadingDraft: Boolean = false

    fun triggerScrollToMessage(messageId: String? = null) {
        viewModelScope.launch {
            _scrollToMessage.emit(messageId)
        }
    }

    private val _currentActiveModel = MutableStateFlow<String?>(null)
    val currentActiveModel = kotlinx.coroutines.flow.combine(_currentActiveModel, settings.selectedModel) { active, default ->
        active ?: default
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Constants.EXAMPLE_MODEL_ID)

    fun getProviderForModel(modelId: String): String = providerRegistry.providerForModel(modelId)
    

        
    // Embedding subsystem state lives in [ragManager]; exposed here for the UI.
    val activeEmbeddingModel get() = ragManager.activeEmbeddingModel
    val cachingProgress get() = ragManager.cachingProgress
    val cacheCounts get() = ragManager.cacheCounts
    fun loadCacheCounts() = ragManager.loadCacheCounts()

    // ── Remote shell command confirmation gate ───────────────────────────
    /** Shell-command confirmation policy + pending-prompt handshake (see [ShellConfirmationController]). */
    private val shellConfirmation = ShellConfirmationController(settings)
    val pendingShellCommand: StateFlow<ShellConfirmationController.PendingShellCommand?>
        get() = shellConfirmation.pendingShellCommand

    /** Called by the UI to resolve a pending confirmation. */
    fun resolveShellConfirmation(allow: Boolean, alwaysAllowServer: Boolean = false) =
        shellConfirmation.resolve(allow, alwaysAllowServer)

    fun setShellConfirmEnabled(enabled: Boolean) = shellConfirmation.setEnabled(enabled)

    // ── Tasks (automation) ────────────────────────────────────
    /** Saved automation tasks; CRUD + run-now delegate to the app-scoped [taskManager]. */
    val tasks: StateFlow<List<com.newoether.agora.data.local.TaskEntity>> get() = taskManager.tasks
    val runningTaskIds: StateFlow<Set<String>> get() = taskManager.runningTaskIds

    fun executionsForTask(taskId: String) = taskManager.executionsForTask(taskId)
    fun executionSummariesForTask(taskId: String) = taskManager.executionSummariesForTask(taskId)
    suspend fun getTask(taskId: String) = taskManager.getTask(taskId)

    fun saveTask(task: com.newoether.agora.data.local.TaskEntity) {
        viewModelScope.launch { taskManager.saveTask(task) }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch { taskManager.deleteTask(taskId) }
    }

    fun runTaskNow(task: com.newoether.agora.data.local.TaskEntity) = taskManager.runNow(task)

    // ── Auto Backup ───────────────────────────────────────────

    val conversations: StateFlow<List<ChatConversation>> = convRepo.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentConversation: StateFlow<ChatConversation?> = _currentConversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else convRepo.observeConversation(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentLoop: StateFlow<com.newoether.agora.data.local.LoopEntity?> = _currentConversationId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                combine(
                    loopManager.loopForConversation(id),
                    loopManager.runningConversationIds,
                ) { loop, runningIds ->
                    // A final cycle claims its durable slot by setting active=false before the
                    // model call. Keep its control bar visible while the Worker is still alive so
                    // the user can stop it instead of losing the only cancellation affordance.
                    loop?.takeIf { it.active || id in runningIds }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val runningLoopConversationIds: StateFlow<Set<String>> get() = loopManager.runningConversationIds

    fun stopCurrentLoop() {
        val id = _currentConversationId.value ?: return
        viewModelScope.launch { loopManager.stopLoop(id) }
    }

    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessages: StateFlow<List<ChatMessage>> = _allMessages.asStateFlow()

    private val _hasOlderMessages = MutableStateFlow(false)
    private val historyPageRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()
    private val _historyLoadError = MutableStateFlow<String?>(null)
    val historyLoadError: StateFlow<String?> = _historyLoadError.asStateFlow()

    /** Request one strict keyset page; loaded pages accumulate without a permanent total cap. */
    fun loadOlderMessages() {
        if (_hasOlderMessages.value) historyPageRequests.tryEmit(Unit)
    }

    /** Last coherent mapped snapshot per conversation. Switching never destroys it pre-emptively. */
    private val coherentMessageSnapshots = java.util.concurrent.ConcurrentHashMap<String, List<ChatMessage>>()

    private val _isSyncingModels = MutableStateFlow(false)
    val isSyncingModels: StateFlow<Boolean> = _isSyncingModels.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>(replay = 1)
    val snackbarMessage = _snackbarMessage.asSharedFlow()
    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        viewModelScope.launch { _snackbarMessage.emit(SnackbarEvent(message, actionLabel, onAction)) }
    }


    /** PDF / text-file preview state (see [MediaPreviewState]). */
    private val mediaPreview = MediaPreviewState()
    val previewPdfPages: StateFlow<List<String>> get() = mediaPreview.pdfPages
    val previewPdfIndex: StateFlow<Int> get() = mediaPreview.pdfIndex
    val previewFileContent: StateFlow<String?> get() = mediaPreview.fileContent
    val previewFileName: StateFlow<String?> get() = mediaPreview.fileName

    fun showPdfPreview(pages: List<String>, startIndex: Int) = mediaPreview.showPdf(pages, startIndex)
    fun showFilePreview(fileName: String, content: String) = mediaPreview.showFile(fileName, content)
    fun clearPreviews() = mediaPreview.clear()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    private val _selectedChildren = MutableStateFlow<Map<String?, String>>(emptyMap())

    val messages: StateFlow<List<ChatMessage>> = combine(
        _allMessages,
        _streamingMessage,
        _selectedChildren
    ) { allMsgs, streaming, selectedChildren ->
        // Single source of truth for the visible-path walk: the tested
        // ConversationUiState.resolvePath (covered by ConversationUiStateTest).
        ConversationUiState.resolvePath(allMsgs, streaming, selectedChildren)
    }.distinctUntilChanged()
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalTokens: StateFlow<Int> = _allMessages.map { list ->
        list.sumOf { it.tokenCount }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _generatingInConversationId = MutableStateFlow<String?>(null)
    val generatingInConversationId: StateFlow<String?> = _generatingInConversationId.asStateFlow()

    /** Per-conversation generation state registry. Each conversation owns an independent
     *  ConversationGenerationState; the global _isLoading/_streamingMessage/_generatingInConversationId
     *  below are now a MIRROR of whichever conversation is currently open (see init collectors). */
    private val generationRegistry = ConversationStateRegistry().also { registry ->
        registry.onStateCreated = { state ->
            state.onActive = { conversationId ->
                registry.markActive(conversationId)
                if (_currentConversationId.value == conversationId) {
                    // Publish the state transition synchronously with the slot claim. Besides
                    // making the Stop button immediate, this closes the one-frame window where
                    // an in-progress edit could remain open after a normal composer Send.
                    _isLoading.value = true
                    _generatingInConversationId.value = conversationId
                }
            }
            state.onIdle = { conversationId ->
                registry.markIdle(conversationId)
                if (_currentConversationId.value == conversationId) {
                    _isLoading.value = false
                    _generatingInConversationId.value = null
                }
            }
            state.onStreamCommit = { conversationId, message ->
                if (_currentConversationId.value == conversationId) {
                    _allMessages.update { messages ->
                        messages.map { if (it.id == message.id) message else it }
                    }
                }
            }
        }
    }

    /** Every conversation currently mutating its message tree through foreground generation or
     * headless Task/Loop execution. Drawer rows use this per-id set instead of the open
     * conversation's `_isLoading` mirror. */
    val generatingConversationIds: StateFlow<Set<String>> = combine(
        generationRegistry.activeConversationIds,
        conversationExecutionCoordinator.activeAutomationConversationIds,
    ) { foreground, automation ->
        foreground + automation
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val generationMirror = ConversationGenerationMirror(
        currentConversationId = _currentConversationId,
        onSnapshot = { conversationId, snapshot ->
            _streamingMessage.value = snapshot.streamingMessage
            _isLoading.value = snapshot.isLoading
            _generatingInConversationId.value =
                if (snapshot.isGenerating) conversationId else null
        },
    )

    /** Stop-finalization helper shared by the controller and the ViewModel's stop path. */
    private val generationFinalizer by lazy {
        GenerationFinalizer(convRepo, settings, ::indexMessageForRag)
    }

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private var switchingJob: Job? = null

    fun setSwitching(switching: Boolean) {
        _isSwitching.value = switching
    }

    private val _isNewChatMode = MutableStateFlow(true)
    val isNewChatMode: StateFlow<Boolean> = _isNewChatMode.asStateFlow()

    private val _isTransitioningToNewChat = MutableStateFlow(false)
    val isTransitioningToNewChat: StateFlow<Boolean> = _isTransitioningToNewChat.asStateFlow()

    private val _pendingSystemPromptId = MutableStateFlow<String?>(null)
    val pendingSystemPromptId: StateFlow<String?> = _pendingSystemPromptId.asStateFlow()

    fun setPendingSystemPrompt(promptId: String?) {
        _pendingSystemPromptId.value = promptId
    }

    private val _pendingConversationSettings = MutableStateFlow<ConversationSettings?>(null)
    val pendingConversationSettings: StateFlow<ConversationSettings?> = _pendingConversationSettings.asStateFlow()

    fun setPendingConversationSettings(settings: ConversationSettings?) {
        _pendingConversationSettings.value = settings
    }

    private val payloadBuilder by lazy {
        MessagePayloadBuilder(
            generationManager = generationManager,
            onSnackbar = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
        )
    }

    private val requestBuilder = GenerationRequestBuilder(
        settings = settings,
        convRepo = convRepo,
        memoryManager = memoryManager,
        providerRegistry = providerRegistry,
        ragManager = ragManager,
        appContext = appContext,
        currentActiveModel = currentActiveModel,
        pendingConversationSettings = _pendingConversationSettings,
        onSnackbar = { msg -> emitSnackbar(msg) },
    )

    private val generationController by lazy {
        MessageGenerationController(
            viewModelScope = viewModelScope,
            application = getApplication(),
            appContext = appContext,
            convRepo = convRepo,
            settings = settings,
            registry = generationRegistry,
            finalizer = generationFinalizer,
            generationManagerProvider = { generationManager },
            requestBuilder = requestBuilder,
            payloadBuilder = payloadBuilder,
            providerRegistry = providerRegistry,
            localProvider = localProvider,
            executionCoordinator = conversationExecutionCoordinator,
            allMessages = _allMessages,
            selectedChildren = _selectedChildren,
            currentConversationId = _currentConversationId,
            isNewChatMode = _isNewChatMode,
            pendingConversationSettings = _pendingConversationSettings,
            pendingSystemPromptId = _pendingSystemPromptId,
            currentActiveModel = currentActiveModel,
            messages = messages,
            onScrollToMessage = { id -> triggerScrollToMessage(id) },
            onSnackbar = { msg -> emitSnackbar(msg) },
            onSnackbarSuspend = { msg -> _snackbarMessage.emit(SnackbarEvent(msg)) },
            onPersistSelectedChildren = { convId, map -> persistSelectedChildren(convId, map) },
            onConversationCreatedBySend = { suppressNextOpenScroll = true },
            onConversationGraduated = ragManager::backfillConversationForRag,
        )
    }

    fun updateConversationSetting(convId: String?, update: (ConversationSettings) -> ConversationSettings) {
        if (convId != null) {
            val current = settings.conversationSettings.value[convId] ?: ConversationSettings()
            settings.setConversationSettings(convId, update(current))
        } else {
            val current = _pendingConversationSettings.value ?: ConversationSettings()
            _pendingConversationSettings.value = update(current)
        }
    }

    private val _branchSwitchTrigger = MutableStateFlow<String?>(null)
    val branchSwitchTrigger: StateFlow<String?> = _branchSwitchTrigger.asStateFlow()

    fun clearBranchSwitchTrigger() {
        _branchSwitchTrigger.value = null
    }

    // Export/Import state lives in [importExport]; exposed here for the UI.
    val exportProgress get() = importExport.exportProgress
    val importProgress get() = importExport.importProgress
    val importManifest get() = importExport.importManifest
    val importPreview get() = importExport.importPreview
    val claudeImportPreview get() = importExport.claudeImportPreview
    val claudeImportProgress get() = importExport.claudeImportProgress
    val claudeImportResult get() = importExport.claudeImportResult
    val gptImportPreview get() = importExport.gptImportPreview
    val gptImportProgress get() = importExport.gptImportProgress
    val gptImportResult get() = importExport.gptImportResult


    private val _conversationCount = MutableStateFlow(0)
    val conversationCount: StateFlow<Int> = _conversationCount.asStateFlow()

    private val _memoryCount = MutableStateFlow(0)
    val memoryCount: StateFlow<Int> = _memoryCount.asStateFlow()

    private val _systemPromptCount = MutableStateFlow(0)
    val systemPromptCount: StateFlow<Int> = _systemPromptCount.asStateFlow()

    init {
        startInitJobs()
        viewModelScope.launch {
            _currentConversationId.collectLatest { id ->
                if (id != null) {
                    coroutineScope {
                        val switchScope = this
                        val state = generationRegistry.getOrCreate(id)
                        // Fix stuck sending states when loading a conversation. Read THIS conversation's
                        // own slot (state.generating), not the global _isLoading mirror: at switch time
                        // the mirror still reflects the previous conversation, so a background
                        // generation in the target conversation would be misread as idle and its
                        // in-flight SENDING message wrongly marked STOPPED.
                        if (!state.generating.value) {
                            convRepo.fixStuckMessages(id)
                        }

                        // Restore selected branches
                        val conversation = convRepo.getConversation(id)
                        if (conversation?.selectedBranchesJson != null) {
                            try {
                                val map = Json.decodeFromString<Map<String, String>>(conversation.selectedBranchesJson)
                                val decodedMap = map.mapKeys { if (it.key == "null") null else it.key }
                                _selectedChildren.value = decodedMap
                            } catch (e: Exception) {
                                _selectedChildren.value = emptyMap()
                            }
                        } else {
                            _selectedChildren.value = emptyMap()
                        }

                        var generationMirrorStarted = false
                        var loadedEntities = emptyList<MessageEntity>()

                        suspend fun mapAndPublish(entities: List<MessageEntity>) {
                            val mapped = withContext(Dispatchers.Default) {
                                entities.mapNotNull { entity -> runCatching {
                                    val decodedSegments = entity.toolCallJson?.let { raw ->
                                        try { Json.decodeFromString<List<MessageSegment>>(raw) }
                                        catch (_: Exception) { null }
                                    }
                                    ChatMessage(
                                        id = entity.id,
                                        parentId = entity.parentId,
                                        text = SearchResultFormatter.format(entity.text, appContext),
                                        images = entity.images,
                                        thoughts = entity.thoughts,
                                        thoughtTitle = entity.thoughtTitle,
                                        tokenCount = entity.tokenCount,
                                        status = entity.status,
                                        participant = entity.participant,
                                        timestamp = entity.timestamp,
                                        thoughtTimeMs = entity.thoughtTimeMs,
                                        modelName = entity.modelName,
                                        segments = decodedSegments ?: entity.thoughts
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { listOf(MessageSegment(type = "thought", content = it)) },
                                        toolCall = decodedSegments?.lastOrNull { it.type == "tool" }?.let { seg ->
                                            ToolCallData(seg.toolName.orEmpty(), seg.toolArgs ?: "{}",
                                                SearchResultFormatter.format(seg.toolResult.orEmpty(), appContext))
                                        },
                                        attachmentMeta = entity.attachmentMeta?.let { raw ->
                                            try { Json.decodeFromString<AttachmentMeta>(raw) }
                                            catch (_: Exception) { null }
                                        }
                                    )
                                }.onFailure { error ->
                                    DebugLog.e("ChatViewModel", "Skipping malformed history row ${entity.id} in $id", error)
                                }.getOrNull() }
                            }
                            val mappedById = mapped.associateBy { it.id }
                            val coherent = mapped.map { msg ->
                                if (msg.id.startsWith(Constants.RESULT_MSG_PREFIX) && msg.toolCall == null) {
                                    mappedById[msg.parentId]?.toolCall?.let { msg.copy(toolCall = it) } ?: msg
                                } else msg
                            }
                            if (_currentConversationId.value != id) return
                            if (entities.isNotEmpty() && coherent.isEmpty()) {
                                _historyLoadError.value = "Conversation rows exist but none could be decoded"
                                coherentMessageSnapshots[id]?.let { _allMessages.value = it }
                                _isSwitching.value = false
                                return
                            }
                            coherentMessageSnapshots[id] = coherent
                            _allMessages.value = coherent
                            _historyLoadError.value = null
                            _isSwitching.value = false
                            if (!generationMirrorStarted) {
                                generationMirrorStarted = true
                                generationMirror.publishCurrent(id, state)
                                switchScope.launch { generationMirror.collect(id, state) }
                            }
                        }

                        suspend fun loadInitialPage() {
                            loadedEntities = convRepo.getNewestMessagesPage(id, INITIAL_MESSAGE_WINDOW)
                            _hasOlderMessages.value = loadedEntities.size == INITIAL_MESSAGE_WINDOW
                            mapAndPublish(loadedEntities)
                        }

                        try {
                            loadInitialPage()
                            historyPageRequests.collect {
                                if (_currentConversationId.value != id || !_hasOlderMessages.value) return@collect
                                val oldest = loadedEntities.firstOrNull() ?: run {
                                    _hasOlderMessages.value = false
                                    return@collect
                                }
                                val older = convRepo.getOlderMessagesPage(
                                    id, oldest.timestamp, oldest.id, MESSAGE_PAGE_SIZE
                                )
                                if (older.isEmpty()) {
                                    _hasOlderMessages.value = false
                                } else {
                                    val knownIds = loadedEntities.asSequence().map { it.id }.toHashSet()
                                    loadedEntities = (older.filterNot { it.id in knownIds } + loadedEntities)
                                        .sortedWith(compareBy<MessageEntity> { it.timestamp }.thenBy { it.id })
                                    _hasOlderMessages.value = older.size == MESSAGE_PAGE_SIZE
                                    mapAndPublish(loadedEntities)
                                }
                            }
                        } catch (cause: CancellationException) {
                            throw cause
                        } catch (cause: Exception) {
                            if (_currentConversationId.value == id) {
                                DebugLog.e("ChatViewModel", "History keyset load failed for $id", cause)
                                _historyLoadError.value = cause.message ?: "Unable to load conversation history"
                                coherentMessageSnapshots[id]?.let { _allMessages.value = it }
                                _isSwitching.value = false
                            }
                        }
                        }
                    }
                } else {
                    _allMessages.value = emptyList()
                    _hasOlderMessages.value = false
                    _selectedChildren.value = emptyMap()
                    _streamingMessage.value = null
                    _isLoading.value = false
                    _generatingInConversationId.value = null
                }
            }
        }
        
    }

    private suspend fun persistSelectedChildren(conversationId: String, childrenMap: Map<String?, String>) {
        convRepo.saveBranchSelections(conversationId, childrenMap)
    }

    // ── Custom providers ──────────────────────────────────────
    // Settings persistence lives in SettingsRepository; ChatViewModel only maintains
    // the live in-memory provider instances (the `providers` map) via callbacks.
    fun addCustomProvider(name: String, baseUrl: String) = providerRegistry.addCustom(name, baseUrl)
    fun renameCustomProvider(oldName: String, newName: String) = providerRegistry.renameCustom(oldName, newName)
    fun deleteCustomProvider(name: String) = providerRegistry.deleteCustom(name)

    fun getCurrentVersion(): String {
        return try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
    }
    fun addEmbeddingModel(config: EmbeddingModelConfig) = ragManager.addEmbeddingModel(config)
    fun deleteEmbeddingModel(id: String) = ragManager.deleteEmbeddingModel(id)
    fun renameEmbeddingModel(id: String, newName: String, batchSize: Int? = null) =
        ragManager.renameEmbeddingModel(id, newName, batchSize)
    fun setActiveEmbeddingModel(id: String) = ragManager.setActiveEmbeddingModel(id)
    fun cacheMessagesForModel(modelId: String, recache: Boolean = false, silent: Boolean = false) =
        ragManager.cacheMessagesForModel(modelId, recache, silent)

    fun isLocalModelIdTaken(modelId: String, excludeId: String? = null) =
        modelManager.isLocalModelIdTaken(modelId, excludeId)
    fun addLocalChatModel(config: LocalChatModelConfig) = modelManager.addLocalChatModel(config)
    fun deleteLocalChatModel(uuid: String) = modelManager.deleteLocalChatModel(uuid)
    fun updateLocalChatModel(
        uuid: String, newModelId: String, newAlias: String, nCtx: Int, temperature: Float, topP: Float, maxTokens: Int,
        mmprojPath: String = ""
    ) = modelManager.updateLocalChatModel(uuid, newModelId, newAlias, nCtx, temperature, topP, maxTokens, mmprojPath)

    suspend fun semanticSearch(query: String, limit: Int = 20): List<Pair<MessageEntity, Float>> {
        val ctx = GenerationContext(
            accessSavedMemories = settings.accessSavedMemories.value,
            accessActiveMemory = settings.accessActiveMemory.value,
            accessPastConversations = settings.accessPastConversations.value,
            modelSearchMethod = settings.modelSearchMethod.value,
            activeEmbeddingConfig = activeEmbeddingModel.value,
            embeddingApiKey = ragManager.resolveEmbeddingApiKey() ?: "",
            ragThreshold = settings.ragThreshold.value,
            searchMatchLimit = settings.searchMatchLimit.value,
            searchContextWindow = settings.searchContextWindow.value,
            webSearchEnabled = settings.webSearchEnabled.value,
            webSearchApiKeys = settings.webSearchApiKeys.value,
            webSearchProvider = settings.webSearchProvider.value,
            webSearchNumResults = settings.webSearchNumResults.value,
            webSearchBaseUrl = settings.webSearchBaseUrl.value
        )
        return generationManager.semanticSearch(query, limit, ctx)
    }

    fun resolveEmbeddingKeyForProviderExact(targetProvider: String) =
        ragManager.resolveEmbeddingKeyForProviderExact(targetProvider)

    fun indexMessageForRag(messageId: String, text: String) = ragManager.indexMessageForRag(messageId, text)
    suspend fun searchMessages(query: String, limit: Int = 20) = convRepo.searchMessages(query, limit)
    // ── Auto Backup ───────────────────────────────────────────
    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveAutoBackupEnabled(enabled)
            if (enabled) {
                try { AutoBackupWorker.schedule(getApplication()) } catch (_: Exception) {}
            } else {
                try { AutoBackupWorker.cancel(getApplication()) } catch (_: Exception) {}
            }
        }
    }
    fun setAutoBackupPeriodHours(hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.saveAutoBackupPeriodHours(hours)
            // Enforce: auto-delete period must be strictly greater than backup period
            val deleteTiers = AUTO_DELETE_TIERS_HOURS
            val deleteHours = settings.autoDeletePeriodHours.value
            if (deleteHours <= hours) {
                val nextDelete = deleteTiers.firstOrNull { it > hours } ?: AUTO_DELETE_TIERS_HOURS.last()
                settings.saveAutoDeletePeriodHours(nextDelete)
            }
        }
    }
    fun setAutoBackupCategories(categories: String) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoBackupCategories(categories) }
    }
    fun setAutoBackupDirectory(path: String) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoBackupDirectory(path) }
    }
    fun setAutoDeleteEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settings.saveAutoDeleteEnabled(enabled) }
    }
    fun setAutoDeletePeriodHours(hours: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val backupHours = settings.autoBackupPeriodHours.value
            val deleteTiers = AUTO_DELETE_TIERS_HOURS
            // Find the smallest valid delete tier that is > backupHours, and >= the requested hours
            val minValid = deleteTiers.firstOrNull { it > backupHours } ?: AUTO_DELETE_TIERS_HOURS.last()
            settings.saveAutoDeletePeriodHours(maxOf(hours, minValid))
        }
    }
    fun addShellDevice(device: ShellDeviceConfig) {
        settings.addShellDevice(device)
    }
    fun updateShellDevice(device: ShellDeviceConfig) {
        settings.updateShellDevice(device)
    }

    /**
     * Connects to an SSH host in capture mode and returns the server host key
     * (base64) together with its SHA-256 fingerprint, for the user to review and
     * pin. The host key is exchanged before authentication, so this succeeds even
     * if the password is wrong — letting the user pin the key first.
     */
    suspend fun verifySshHostKey(
        host: String, port: Int, user: String, password: String, timeoutSec: Int
    ): Result<Pair<String, String>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext Result.failure(Exception("Host is empty"))
        val client = SshClient(
            host, port, user.ifBlank { "root" }, password, timeoutSec * 1000,
            pinnedHostKey = "", allowUnknownHostKey = true
        )
        try {
            client.executeCommand("true")
        } catch (_: Exception) {
            // Ignore — the host key is captured during the handshake regardless of auth result.
        } finally {
            client.close()
        }
        val key = client.capturedHostKey
        if (key.isNullOrBlank()) Result.failure(Exception("Could not reach host or no host key presented"))
        else Result.success(key to SshClient.fingerprintSha256(key))
    }
    suspend fun testRemoteEmbedding(modelName: String, baseUrl: String, apiKey: String = ""): String? {
        val effectiveKey = apiKey.ifBlank { ragManager.resolveEmbeddingApiKey() ?: "" }
        val url = baseUrl.ifBlank { ragManager.resolveEmbeddingBaseUrl() }
        return withContext(Dispatchers.IO) {
            try {
                val result = EmbeddingClient.computeEmbedding("test connection", effectiveKey, modelName, url)
                if (result != null) "OK (dim=${result.size})" else "Request failed. Check API key, URL, and model name."
            } catch (e: Exception) {
                e.message ?: "Error"
            }
        }
    }

    fun createNewChat() {
    if (_isNewChatMode.value) return
    switchingJob?.cancel()
    _pendingSystemPromptId.value = null
    _isNewChatMode.value = true
    _isTransitioningToNewChat.value = true
    _isSwitching.value = true

    // Identity changes are synchronous. A delayed reset could otherwise clear the id
    // of a conversation created by a fast first Send during the fade animation.
    _currentConversationId.value = null
    _currentActiveModel.value = null
    _pendingConversationSettings.value = null
    _allMessages.value = emptyList()
    _selectedChildren.value = emptyMap()
    _branchSwitchTrigger.value = null

    switchingJob = viewModelScope.launch {
        kotlinx.coroutines.delay(SWITCH_OVERLAY_FADE_MS)
        _isSwitching.value = false
        _isTransitioningToNewChat.value = false
    }
}

fun selectConversation(id: String) {
        if (_currentConversationId.value == id && !_isNewChatMode.value) return

        switchingJob?.cancel()
        _isTransitioningToNewChat.value = false
        _isSwitching.value = true
        // Query Room immediately; animate the switching overlay concurrently.
        _isNewChatMode.value = false
        _branchSwitchTrigger.value = null
        _historyLoadError.value = null
        // Do not clear the visible snapshot before Room has produced the target conversation's
        // first coherent result. The switching scrim covers the old snapshot during this interval.
        coherentMessageSnapshots[id]?.let { _allMessages.value = it }
        _hasOlderMessages.value = false
        _currentConversationId.value = id
        switchingJob = viewModelScope.launch {
            val conversation = convRepo.getConversation(id)
            _currentActiveModel.value = conversation?.modelId
            triggerScrollToMessage()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            val existing = convRepo.getConversation(id)
            if (existing != null) {
                convRepo.upsertConversation(existing.copy(title = newTitle))
            }
        }
    }

    fun generateTitle(conversationId: String) = generationController.generateTitle(conversationId)

    fun setConversationSystemPrompt(id: String, promptId: String?) {
        viewModelScope.launch {
            val existing = convRepo.getConversation(id)
            if (existing != null) {
                convRepo.upsertConversation(existing.copy(systemPromptId = promptId))
            }
        }
    }

    fun setActiveModel(model: String) {
        _currentActiveModel.value = model
        _currentConversationId.value?.let { id ->
            viewModelScope.launch {
                val existing = convRepo.getConversation(id)
                if (existing != null) {
                    convRepo.upsertConversation(existing.copy(modelId = model))
                }
            }
        }
    }

    fun deleteConversation(id: String) {
        if (_currentConversationId.value == id) {
            stopGeneration()
        }
        viewModelScope.launch(Dispatchers.IO) {
            loopManager.stopLoop(id)
            conversationExecutionCoordinator.withConversationLock(id) {
                convRepo.deleteConversation(id)
            }
            generationRegistry.remove(id)
            if (_currentConversationId.value == id) {
                withContext(Dispatchers.Main) { createNewChat() }
            }
        }
    }

    /**
     * Deletes a message and all its descendants (BFS cascade).
     * Hidden tool_/result_ children are included in the cascade.
     * Attachments, embeddings, and branch selections are cleaned up.
     * Returns the count of deleted messages (for the confirmation dialog).
     */
    fun deleteMessage(messageId: String): Int = generationController.deleteMessage(messageId)

    /** Queued sends for the currently-open conversation (drives the queue banner above the input). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val queuedSends: StateFlow<List<QueuedSend>> = _currentConversationId
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else generationRegistry.getOrCreate(id).queuedSends
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun removeQueuedSend(id: String) {
        val removed = _currentConversationId.value?.let {
            generationRegistry.getOrCreate(it).removeQueuedSend(id)
        } ?: return
        // The queued send held the only reference to its copied attachment files (the composer
        // cleared its own on enqueue). It was never sent → delete them so they don't orphan.
        com.newoether.agora.util.AttachmentFiles.deleteBacking(removed.attachments)
    }

    fun clearQueuedSends() {
        val removed = _currentConversationId.value?.let {
            generationRegistry.getOrCreate(it).clearQueuedSends()
        } ?: return
        removed.forEach { com.newoether.agora.util.AttachmentFiles.deleteBacking(it.attachments) }
    }

    fun stopGeneration() {
        // Stop the CURRENTLY-OPEN conversation's generation only. A background conversation's
        // generation is intentionally not killed here — the user is asking to stop what they
        // see. registry.stop() cancels that conversation's job + streamScope (not other
        // conversations'), and finalizer persists STOPPED to the correct conversation id.
        val id = _currentConversationId.value ?: return
        val state = generationRegistry.get(id) ?: return
        val result = state.stop()
        val stoppedMsg = result.stoppedMessage
        val messages = if (stoppedMsg != null) listOf(stoppedMsg) else {
            // streamingMessage was null — mark any in-flight model message in the open list directly.
            _allMessages.value.mapNotNull { m ->
                if (m.participant == Participant.MODEL &&
                    (m.status == MessageStatus.SENDING || m.status == MessageStatus.THINKING ||
                        m.status == MessageStatus.TOOL_CALLING || m.status == MessageStatus.TRANSCRIBING)
                ) {
                    val stopped = m.copy(status = MessageStatus.STOPPED)
                    _allMessages.update { list -> list.map { if (it.id == m.id) stopped else it } }
                    stopped
                } else null
            }
        }
        if (messages.isNotEmpty()) {
            generationFinalizer.launchStopFinalization(state.scope, result.conversationId, messages)
        }
    }

    fun regenerate(messageId: String) = generationController.regenerate(messageId)

    fun switchBranch(parentId: String?, currentMessageId: String, direction: Int) {
    val conversationId = _currentConversationId.value ?: return
    if (_isLoading.value && _generatingInConversationId.value == conversationId) return
        val siblings = _allMessages.value.filter { it.parentId == parentId && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }.sortedBy { it.timestamp }
        if (siblings.size < 2) return
        var currentIndex = siblings.indexOfFirst { it.id == currentMessageId }
        if (currentIndex == -1) {
            val selectedId = _selectedChildren.value[parentId]
            currentIndex = siblings.indexOfFirst { it.id == selectedId }
        }
        if (currentIndex == -1) return
        val newIndex = (currentIndex + direction).coerceIn(0, siblings.size - 1)
        if (newIndex == currentIndex) return
        
        switchingJob?.cancel()
        _isSwitching.value = true
        switchingJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SWITCH_OVERLAY_FADE_MS) // Allow overlay to fade in
            val newMap = _selectedChildren.value.toMutableMap()
            val targetMessage = siblings[newIndex]
            newMap[parentId] = targetMessage.id
    // Bind persistence to the conversation where the action started.
    persistSelectedChildren(conversationId, newMap)
    if (_currentConversationId.value != conversationId) {
        _isSwitching.value = false
        return@launch
    }
    _selectedChildren.value = newMap
            
            _branchSwitchTrigger.value = null
            _branchSwitchTrigger.value = targetMessage.id
        }
    }

    fun editMessage(messageId: String, newText: String) = generationController.editMessage(messageId, newText)

    private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
        return try {
            val cursor = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) ?: uri.lastPathSegment ?: "unknown"
                    else uri.lastPathSegment ?: "unknown"
                } else uri.lastPathSegment ?: "unknown"
            } ?: (uri.lastPathSegment ?: "unknown")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "unknown"
        }
    }

    fun sendMessage(text: String, images: List<String> = emptyList(), attachments: List<SelectedAttachment> = emptyList()): Boolean {
        val sent = generationController.sendMessage(text, images, attachments)
        if (sent) {
            // The message has left the composer (launched or queued) — clear this conversation's
            // persisted draft so switching back doesn't restore text the user already sent.
            val id = _currentConversationId.value
            if (id != null) {
                viewModelScope.launch {
                    runCatching { convRepo.updateDraft(id, "", null) }
                    // Reset the anti-loop snapshot so the next real edit writes through.
                    lastLoadedDraft = "" to emptyList()
                }
            }
        }
        return sent
    }

    /**
     * Onboarding-focused model fetch for a single provider.
     *
     * Unlike [fetchAvailableModels] this carries no global side effects: no
     * `_isSyncingModels` guard (so re-entry always refetches the latest key),
     * no enabled-set intersection, and no snackbar. It is a plain suspend
     * function so the caller's coroutine owns its lifecycle — cancelling that
     * coroutine cooperatively aborts the in-flight network request, which keeps
     * the welcome flow seamless (no stale result can land after the user edits
     * their key and returns). Results are persisted so the [availableModels]
     * flow updates the list. Returns the prefixed model ids, or empty on
     * failure / unconfigured provider.
     */
    suspend fun fetchModelsForProvider(name: String): List<String> = providerRegistry.fetchModelsForProvider(name)

    fun computeProviderFingerprint(): String = providerRegistry.computeFingerprint()

    fun fetchAvailableModels() {
        viewModelScope.launch {
            if (_isSyncingModels.value) return@launch
            _isSyncingModels.value = true
            val successProviders = mutableListOf<String>()
            val failedProviders = mutableListOf<String>()
            var skippedCount = 0

            // Ensure custom providers are loaded into the providers map before iterating
            providerRegistry.ensureCustomProvidersRegistered()

            val message = try {
                providerRegistry.all.forEach { (name, _) ->
                    if (name == Constants.PROVIDER_LOCAL) return@forEach

                    try {
                        if (!providerRegistry.isConfigured(name, settings.resolveActiveKey(name) ?: "")) {
                            skippedCount++
                            settings.saveAvailableModels(name, emptyList())
                            return@forEach
                        }

                        val models = providerRegistry.fetchModelsForProvider(name)
                        if (models.isNotEmpty()) {
                            successProviders.add(name)
                        } else {
                            failedProviders.add(name)
                        }
                    } catch (e: Exception) {
                        failedProviders.add(name)
                    }
                }

                val allFetchedModels = settings.getAvailableModels().values.flatten().toSet()
                val newEnabled = settings.enabledModels.value.intersect(allFetchedModels)
                settings.setEnabledModels(newEnabled)

                // Save fingerprint on any successful fetch so we don't re-fetch on next visit
                settings.saveLastModelsFetchFingerprint(computeProviderFingerprint())

                when {
                    successProviders.isNotEmpty() && failedProviders.isEmpty() ->
                        appContext.getString(R.string.sync_success_providers, successProviders.size)
                    successProviders.isNotEmpty() && failedProviders.isNotEmpty() ->
                        appContext.getString(R.string.sync_partial, successProviders.joinToString(), failedProviders.joinToString())
                    successProviders.isEmpty() && failedProviders.isNotEmpty() ->
                        appContext.getString(R.string.sync_failed_providers, failedProviders.joinToString())
                    else -> if (skippedCount > 0) appContext.getString(R.string.sync_no_providers) else appContext.getString(R.string.sync_completed)
                }
            } catch (e: Exception) {
                appContext.getString(R.string.sync_failed_providers, e.message ?: appContext.getString(R.string.unknown_error))
            } finally {
                _isSyncingModels.value = false
            }

            _snackbarMessage.tryEmit(SnackbarEvent(message))
        }
    }

    // ---- Data Control: Export / Import ----

    fun refreshDataCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            _conversationCount.value = convRepo.getAllConversationsList().size
            _memoryCount.value = memoryManager.listFiles().size +
                (if (memoryManager.getActiveMemory().isNotEmpty()) 1 else 0)
            _systemPromptCount.value = settings.getSystemPrompts().size
        }
    }

    fun exportData(uri: Uri, categories: Set<DataExporter.ExportCategory>, includeApiKeys: Boolean) =
        importExport.exportData(uri, categories, includeApiKeys)
    fun previewImport(uri: Uri) = importExport.previewImport(uri)
    fun clearImportState() = importExport.clearImportState()
    fun setClaudeImportPreview(preview: ClaudeChatImporter.ImportPreview) = importExport.setClaudeImportPreview(preview)
    fun previewClaudeChat(uri: Uri) = importExport.previewClaudeChat(uri)
    fun setClaudeImportError(error: String) = importExport.setClaudeImportError(error)
    fun clearClaudeImportState() = importExport.clearClaudeImportState()
    fun importClaudeChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) =
        importExport.importClaudeChat(uri, strategy, selectedIds)
    fun previewGptChat(uri: Uri) = importExport.previewGptChat(uri)
    fun setGptImportError(error: String) = importExport.setGptImportError(error)
    fun clearGptImportState() = importExport.clearGptImportState()
    fun importGptChat(uri: Uri, strategy: ImportStrategy, selectedIds: Set<String>) =
        importExport.importGptChat(uri, strategy, selectedIds)
    fun importData(uri: Uri, decisions: Map<DataExporter.ExportCategory, DataImporter.ImportStrategy>) =
        importExport.importData(uri, decisions)

    // ── Per-conversation draft persistence ─────────────────────

    /** Snapshot of the last loaded draft; used to suppress write-back of unchanged values
     *  (anti-loop: loading from DB must not trigger a write back to DB). */
    @Volatile
    var lastLoadedDraft: Pair<String, List<SelectedAttachment>>? = null

    /** Persist the composer text and attachment list for a conversation. Fire-and-forget on
     *  viewModelScope; the UI call site handles debouncing before calling this. */
    fun updateDraft(conversationId: String, text: String, attachments: List<SelectedAttachment>) {
        val last = lastLoadedDraft
        if (last != null && last.first == text && last.second == attachments) return
        viewModelScope.launch(Dispatchers.IO) {
            val json = if (attachments.isEmpty()) null
            else Json.encodeToString(attachments)
            convRepo.updateDraft(conversationId, text, json)
        }
    }

    /** Load a stored draft for [conversationId]. Returns [draftText] and deserialized
     *  [SelectedAttachment] list (empty if none stored). The caller must set [loadingDraft] before
     *  mutating UI fields with the result, to suppress the write-back snapshotFlow. */
    suspend fun loadDraft(conversationId: String): Pair<String, List<SelectedAttachment>> {
        val entity = convRepo.getConversation(conversationId) ?: run {
            lastLoadedDraft = "" to emptyList()
            return "" to emptyList()
        }
        val attachments: List<SelectedAttachment> = try {
            entity.draftAttachments?.let { Json.decodeFromString<List<SelectedAttachment>>(it) } ?: emptyList()
        } catch (e: Exception) {
            DebugLog.w("ChatViewModel", "Failed to deserialize draft attachments for $conversationId", e)
            emptyList()
        }
        val draft = entity.draftText to attachments
        lastLoadedDraft = draft
        return draft
    }
}
