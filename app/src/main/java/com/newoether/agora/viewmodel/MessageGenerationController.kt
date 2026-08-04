package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import com.newoether.agora.R
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.data.BuiltInPrompts
import com.newoether.agora.data.ConversationSettings
import com.newoether.agora.data.local.ChatEntity
import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Owns the message lifecycle (send / regenerate / edit / delete) and the
 * race-free generation handshake. Extracted VERBATIM from ChatViewModel.
 * Holds references to the SAME MutableStateFlow instances that ChatViewModel
 * exposes — do NOT create new ones here.
 *
 * Generation state is held per-conversation in [ConversationGenerationState]
 * (obtained from [ConversationStateRegistry]); the global StateFlows
 * ChatViewModel exposes to the UI are a mirror of whichever conversation is
 * currently open. Synchronous writes to the global flows inside the generation
 * coroutines are gated on the open conversation via [ifOpenOn] so a background
 * generation can't clobber the visible conversation's UI.
 */
class MessageGenerationController(
    // ── 协程作用域(用 viewModelScope 传进来)──
    private val viewModelScope: CoroutineScope,
    private val application: Application,
    private val appContext: Context,
    // ── 单例协作者 ──
    private val convRepo: ConversationRepository,
    private val settings: SettingsRepository,
    private val registry: ConversationStateRegistry,
    private val finalizer: GenerationFinalizer,
    private val generationManagerProvider: () -> GenerationManager,
    private val requestBuilder: GenerationRequestBuilder,
    private val payloadBuilder: MessagePayloadBuilder,
    private val providerRegistry: ProviderRegistry,
    private val localProvider: LocalProvider,
    private val executionCoordinator: ConversationExecutionCoordinator,
    // ── 共享 UI 状态:必须是 ChatViewModel 里的同一个实例 ──
    private val allMessages: MutableStateFlow<List<ChatMessage>>,          // = _allMessages
    private val selectedChildren: MutableStateFlow<Map<String?, String>>,  // = _selectedChildren
    private val currentConversationId: MutableStateFlow<String?>,          // = _currentConversationId
    private val isNewChatMode: MutableStateFlow<Boolean>,                  // = _isNewChatMode
    private val pendingConversationSettings: MutableStateFlow<ConversationSettings?>, // = _pendingConversationSettings
    private val pendingSystemPromptId: MutableStateFlow<String?>,          // = _pendingSystemPromptId
    private val currentActiveModel: StateFlow<String>,                     // = currentActiveModel(只读)
    private val messages: StateFlow<List<ChatMessage>>,                    // = messages(只读)
    // ── 回调:替换掉方法体里对 ChatViewModel 私有成员/方法的调用 ──
    private val onScrollToMessage: (String?) -> Unit,    // 替换 triggerScrollToMessage(...)
    private val onSnackbar: (String) -> Unit,            // 替换 emitSnackbar(...)
    private val onSnackbarSuspend: suspend (String) -> Unit,  // generateTitle 内的顺序 emit(等价原版 _snackbarMessage.emit）
    private val onPersistSelectedChildren: suspend (String, Map<String?, String>) -> Unit,
    // Called when sendMessage creates a NEW conversation, so the UI can suppress the
    // conversation-open auto-scroll (the send's own scroll-to-message handles it) and
    // avoid a double scroll on the first message of a new chat.
    private val onConversationCreatedBySend: () -> Unit = {},
    // Called once when a hidden task/loop execution becomes searchable. The callback
    // only enqueues background work; embedding computation must not run under the send lock.
    private val onConversationGraduated: (String) -> Unit = {},
) {
    private val generationManager: GenerationManager get() = generationManagerProvider()

    /**
     * Run [block] only if the currently-open conversation is [genId]. Guards synchronous
     * writes to the shared global flows so a background generation (operating on its own
     * private [ConversationGenerationState] flows) cannot clobber the visible conversation's UI.
     */
    private fun ifOpenOn(genId: String, block: () -> Unit) {
        if (currentConversationId.value == genId) block()
    }

    // ════════════════════════════════════════════════════════════════════
    // deleteMessage
    // ════════════════════════════════════════════════════════════════════

    /**
     * Deletes a message and all its descendants (BFS cascade).
     * Hidden tool_/result_ children are included in the cascade.
     * Attachments, embeddings, and branch selections are cleaned up.
     * Returns the count of deleted messages (for the confirmation dialog).
     */
    fun deleteMessage(messageId: String): Int {
        val currentId = currentConversationId.value ?: return 0

        // Synchronous snapshot for dialog count return — must stay on the calling thread.
        val snapshot = allMessages.value
        val targetMsg = snapshot.find { it.id == messageId } ?: return 0

        val previewIds = linkedSetOf(messageId)
        val queue = mutableListOf(messageId)
        while (queue.isNotEmpty()) {
            val pid = queue.removeAt(0)
            snapshot.filter { it.parentId == pid }.forEach {
                if (previewIds.add(it.id)) queue.add(it.id)
            }
        }

        // P1: Only stop generation if deleting within the currently-generating conversation.
        // P0: stop() + join() prevents the STOPPED-upsert race that can resurrect deleted messages
        //     (the only write path that was missing it).
        val stopFinalization: Job? = if (registry.isActive(currentId)) {
            val state = registry.getOrCreate(currentId)
            // Delete inside the generating conversation is a terminal stop: fully release the slot.
            val r = state.stop()
            val msgs = listOfNotNull(r.stoppedMessage)
            if (msgs.isNotEmpty()) finalizer.launchStopFinalization(state.scope, r.conversationId, msgs) else null
        } else {
            null
        }

        viewModelScope.launch(Dispatchers.IO) {
            executionCoordinator.withConversationLock(currentId) lock@ {
            // Wait for STOPPED DB finalization to complete before deleting.
            // Without this join, a concurrent upsertMessage from stop finalization
            // could resurrect the deleted row as a zombie/orphan after our DELETE.
            stopFinalization?.join()

            // Recompute from the target conversation's DB snapshot. The user may have switched
            // conversations while this coroutine was waiting for generation/finalization.
            val allMsgs = convRepo.getMessagesForConversationSnapshot(currentId)
            if (allMsgs.none { it.id == messageId }) return@lock  // already deleted during wait
            val staleIds = linkedSetOf(messageId)
            val queue = mutableListOf(messageId)
            while (queue.isNotEmpty()) {
                val pid = queue.removeAt(0)
                allMsgs.filter { it.parentId == pid }.forEach {
                    if (staleIds.add(it.id)) queue.add(it.id)
                }
            }

            val staleList = allMsgs.filter { it.id in staleIds }
            convRepo.deleteMessageFiles(staleList)

            // Delete embeddings for all cascaded messages
            for (id in staleIds) {
                convRepo.deleteEmbedding(id)
            }

            // DB delete
            convRepo.deleteMessagesByIds(staleIds.toList())

            // Fix selectedChildren — remove entries where key or value is deleted.
            // If a deleted message was the selected branch, switch to the next available sibling.
            val remainingMsgs = allMsgs.filter { it.id !in staleIds }
            val previousSelected = convRepo.restoreBranchSelections(currentId)
            val newSelected = previousSelected.toMutableMap()
            for ((parentId, childId) in previousSelected) {
                // Remove entry if the parent itself was deleted
                if (parentId != null && parentId in staleIds) {
                    newSelected.remove(parentId)
                    continue
                }
                if (childId in staleIds) {
                    val siblings = remainingMsgs.filter {
                        it.parentId == parentId &&
                            !it.id.startsWith(Constants.TOOL_MSG_PREFIX) &&
                            !it.id.startsWith(Constants.RESULT_MSG_PREFIX)
                    }.sortedBy { it.timestamp }
                    if (siblings.isNotEmpty()) {
                        newSelected[parentId] = siblings.last().id
                    } else {
                        newSelected.remove(parentId)
                    }
                }
            }
            onPersistSelectedChildren(currentId, newSelected)
            ifOpenOn(currentId) {
                allMessages.update { it.filter { message -> message.id !in staleIds } }
                selectedChildren.value = newSelected
            }
            }
        }

        return previewIds.size
    }

    // ════════════════════════════════════════════════════════════════════
    // regenerate
    // ════════════════════════════════════════════════════════════════════

    fun regenerate(messageId: String) {
        val genId = currentConversationId.value ?: return
        val state = registry.getOrCreate(genId)
        val modelId = currentActiveModel.value
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return

        // Validate and snapshot the open conversation BEFORE claiming the slot. The generation
        // coroutine may wait behind automation while the user switches to another conversation.
        val openMessages = allMessages.value
        val selectedAtStart = selectedChildren.value.toMap()
        // Validate the target BEFORE claiming the slot: the claim keeps generating=true
        // for the incoming generation, so an early return after it would leak the slot forever.
        val messageToRegenerate = openMessages.find { it.id == messageId } ?: return
        val parentId = messageToRegenerate.parentId ?: return
        val isErrorOrStopped = messageToRegenerate.status == MessageStatus.ERROR || messageToRegenerate.status == MessageStatus.STOPPED
        val isLatest = openMessages.none { it.parentId == messageId && !it.id.startsWith(Constants.TOOL_MSG_PREFIX) && !it.id.startsWith(Constants.RESULT_MSG_PREFIX) }
        // Error/stopped: purge and replace in-place. Normal: create new branch.
        val modelMessageId = if (isErrorOrStopped && isLatest) messageId else UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis() + 1

        // Regenerate is idle-only by product rule. Enforce it atomically in the state machine in
        // addition to the UI's enabled flag, which can lag during a conversation switch.
        val myUiToken = state.tryAcquireForReplacement() ?: return

        // Insert placeholder into allMessages and update selectedChildren on the calling
        // thread BEFORE setting streamingMessage. This ensures the combine function sees a
        // consistent state where the new ID is both present and selected, avoiding a frame
        // where two model messages appear in the path.
        val placeholder = ChatMessage(
            id = modelMessageId, parentId = parentId, text = "", participant = Participant.MODEL,
            status = MessageStatus.SENDING, timestamp = startTime
        )
        ifOpenOn(genId) { allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder } }
        val newMap = selectedAtStart.toMutableMap()
        newMap[parentId] = modelMessageId
        val selectedAfterRegenerate = newMap.toMap()
        ifOpenOn(genId) { selectedChildren.value = selectedAfterRegenerate }

        state.streamUpdate(myUiToken, placeholder)

        state.generationJob = state.scope.launch {
            val myPersistId = state.nextPersistId()
            try {
                executionCoordinator.withConversationLock(genId) lock@ {
                val persistedMessages = convRepo.getMessagesForConversationSnapshot(genId)
                if (persistedMessages.none { it.id == parentId }) return@lock

                if (isErrorOrStopped && isLatest) {
                    // Purge stale tool call children, thinking content, and embeddings
                    val staleIds = mutableListOf<String>()
                    val queue = mutableListOf(modelMessageId)
                    while (queue.isNotEmpty()) {
                        val pid = queue.removeAt(0)
                        persistedMessages.filter { it.parentId == pid && (it.id.startsWith(Constants.TOOL_MSG_PREFIX) || it.id.startsWith(Constants.RESULT_MSG_PREFIX)) }
                            .forEach { staleIds.add(it.id); queue.add(it.id) }
                    }
                    if (staleIds.isNotEmpty()) {
                        convRepo.deleteMessagesByIds(staleIds)
                        ifOpenOn(genId) { allMessages.update { it.filter { m -> m.id !in staleIds } } }
                    }
                    convRepo.deleteEmbedding(modelMessageId)
                    convRepo.upsertMessage(MessageEntity(
                        id = modelMessageId, conversationId = genId, parentId = parentId,
                        text = "", thoughts = null, thoughtTitle = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                        modelName = modelId, toolCallJson = null
                    ))
                } else {
                    // New branch — old message and its tool calls stay as a selectable branch
                    convRepo.upsertMessage(MessageEntity(
                        id = modelMessageId, conversationId = genId, parentId = parentId,
                        text = "", thoughts = null, thoughtTitle = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                        modelName = modelId
                    ))
                }
                onPersistSelectedChildren(genId, selectedAfterRegenerate)
                convRepo.getConversation(genId)?.let { conv ->
                    convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
                }
                launchGeneration(
                    genId, modelMessageId, startTime,
                    isRegenerate = true, replaceMessageId = messageId,
                    providerName, modelId, activeKey, myUiToken, myPersistId,
                    state, callerTag = "regenerate"
                )
                }
            } finally {
                releaseAndDrain(state, myUiToken, genId)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // launchGeneration
    // ════════════════════════════════════════════════════════════════════

    /**
     * Shared generation tail called by [sendMessage], [regenerate], and
     * [editMessage]: resolves system prompt + conversation settings, builds
     * [GenerationConfig]/[GenerationContext], and launches the provider stream.
     *
     * All three entry points converge here after their differing branch-setup
     * heads, eliminating copy-pasted prompt-resolution / config-building /
     * callback-wiring code.
     */
    private suspend fun launchGeneration(
        currentId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        providerName: String,
        modelId: String,
        activeKey: String,
        uiToken: Long,
        persistId: Long,
        state: ConversationGenerationState,
        callerTag: String
    ) {
        val resolved = requestBuilder.buildEffectiveSystemPrompt(currentId)
        val effectiveSettings = requestBuilder.buildEffectiveConversationSettings(currentId)
        // Re-resolve the key against on-disk settings here (the suspend convergence
        // point for all entry paths). The synchronous [activeKey] resolved by the
        // callers can be blank if DataStore had not finished loading when Send was
        // tapped, which would build the request with an empty key → 401.
        val freshKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() } ?: activeKey
        val (config, genCtx) = requestBuilder.buildGenerationPair(
            providerName, modelId, freshKey,
            resolved.systemPrompt, resolved.userPrepend, resolved.userPostpend,
            effectiveSettings, currentId
        )
        try {
            // No global slot: remote generations run concurrently (only the per-conversation
            // lock above serializes same-conversation work); local model work is serialized
            // inside LocalProvider via LocalModelSerializer. Stop therefore releases
            // immediately — nothing is queued behind a held process-wide mutex.
            generationManager.generate(
                conversationId = currentId,
                modelMessageId = modelMessageId,
                startTime = startTime,
                isRegenerate = isRegenerate,
                replaceMessageId = replaceMessageId,
                modelName = modelId,
                config = config,
                ctx = genCtx,
                generationJob = state.generationJob,
                callbacks = state.callbacksFor(uiToken, persistId),
                streamScope = state.streamScope
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("AgoraVM", "Generation failed in $callerTag", e)
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // editMessage
    // ════════════════════════════════════════════════════════════════════

    fun editMessage(messageId: String, newText: String) {
        if (newText.isBlank()) return
        val genId = currentConversationId.value ?: return
        val state = registry.getOrCreate(genId)
        val modelId = currentActiveModel.value
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: return
        val messageToEdit = allMessages.value.find { it.id == messageId } ?: return
        val selectedAtStart = selectedChildren.value.toMap()

        // Edit is idle-only by product rule; enforce it atomically below the UI gate.
        val myUiToken = state.tryAcquireForReplacement() ?: return
        state.generationJob = state.scope.launch {
            val myPersistId = state.nextPersistId()
            try {
            executionCoordinator.withConversationLock(genId) lock@ {
            if (convRepo.getMessagesForConversationSnapshot(genId).none { it.id == messageId }) {
                return@lock
            }
            val newUserMessageId = UUID.randomUUID().toString()
            convRepo.upsertMessage(MessageEntity(
                id = newUserMessageId, conversationId = genId, parentId = messageToEdit.parentId,
                text = newText, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis()
            ))
            val newMap = selectedAtStart.toMutableMap()
            newMap[messageToEdit.parentId] = newUserMessageId
            val selectedAfterUserEdit = newMap.toMap()
            onPersistSelectedChildren(genId, selectedAfterUserEdit)
            ifOpenOn(genId) { selectedChildren.value = selectedAfterUserEdit }
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            convRepo.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = genId, parentId = newUserMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = modelId
            ))
            convRepo.getConversation(genId)?.let { conv ->
                convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
            }
            // Set streamingMessage BEFORE allMessages so the combine never
            // evaluates with stale allMessages data but no streaming overlay.
            val placeholder = ChatMessage(
                id = modelMessageId, parentId = newUserMessageId, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = modelId
            )
            state.streamUpdate(myUiToken, placeholder)
            ifOpenOn(genId) { allMessages.update { it.filter { m -> m.id != modelMessageId } + placeholder } }
            val editChildren = selectedAfterUserEdit.toMutableMap()
            editChildren[newUserMessageId] = modelMessageId
            val selectedAfterModelEdit = editChildren.toMap()
            onPersistSelectedChildren(genId, selectedAfterModelEdit)
            ifOpenOn(genId) { selectedChildren.value = selectedAfterModelEdit }
            launchGeneration(
                genId, modelMessageId, startTime,
                isRegenerate = false, replaceMessageId = null,
                providerName, modelId, activeKey, myUiToken, myPersistId,
                state, callerTag = "editMessage"
            )
            }
            } finally {
                releaseAndDrain(state, myUiToken, genId)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // sendMessage
    // ════════════════════════════════════════════════════════════════════

    fun sendMessage(text: String, images: List<String> = emptyList(), attachments: List<SelectedAttachment> = emptyList()): Boolean {
        val selectedModelId = currentActiveModel.value
        // Pre-flight: a blank model fails fast BEFORE creating a new-chat row or enqueueing, so the
        // Send button never swallows a message into a conversation that can't generate.
        if (selectedModelId.isBlank()) {
            onSnackbar(application.getString(R.string.no_model_selected))
            return false
        }
        // G9: resolve the conversation id on the calling thread BEFORE launching the generation
        // coroutine, so the registry keys the new generation on the correct conversation even if
        // the user switches chats before the coroutine runs. The new-conversation row is a fast DB
        // insert; doing it here closes the race where genId was unknown on the calling thread.
        val wasNewChat = isNewChatMode.value || currentConversationId.value == null
        if (wasNewChat) {
            val newId = UUID.randomUUID().toString()
            runBlocking {
                convRepo.upsertConversation(ChatEntity(
                    id = newId,
                    title = appContext.getString(R.string.new_chat),
                    modelId = selectedModelId,
                    systemPromptId = pendingSystemPromptId.value
                ))
            }
            // Suppress the conversation-open auto-scroll BEFORE the id change triggers it.
            onConversationCreatedBySend()
            currentConversationId.value = newId
            isNewChatMode.value = false
        }
        val genId = currentConversationId.value ?: return false
        return sendInto(genId, wasNewChat, text, images, attachments, selectedModelId)
    }

    /** Release [uiToken]'s slot and, only if this call actually released it, drain the next queued
     *  send into its originating conversation. */
    private fun releaseAndDrain(state: ConversationGenerationState, uiToken: Long, genId: String) {
        if (state.endGeneration(uiToken)) {
            state.dequeueSend()?.let { queued ->
                // Re-enter with the ORIGINATING genId (never re-reading currentConversationId), so a
                // message queued in conversation A can't land in B after the user switches chats.
                sendInto(
                    genId = genId,
                    wasNewChat = false,
                    text = queued.text,
                    images = emptyList(),
                    attachments = queued.attachments,
                    modelId = queued.modelId,
                )
            }
        }
    }
    /**
     * Core send into a KNOWN conversation [genId] (never re-reads currentConversationId, so a
     * background/drained send lands in its own conversation). Atomically claims the generation slot
     * via [ConversationGenerationState.acquireForSend]: if a generation is already running the
     * message is enqueued (carrying its full attachment list) and this returns true; otherwise the
     * slot is held, generating is set synchronously, and the generation launches. The finally
     * releases the slot (token-gated) and drains the next queued send.
     */
    private fun sendInto(
        genId: String,
        wasNewChat: Boolean,
        text: String,
        images: List<String>,
        attachments: List<SelectedAttachment>,
        modelId: String,
    ): Boolean {
        val state = registry.getOrCreate(genId)
        // Atomic launch-or-enqueue decision. null → a generation already owns this conversation's
        // tree; enqueue behind it (attachments carried whole) and let the drain send it in order.
        val myUiToken = state.acquireForSend() ?: run {
            state.enqueueSend(QueuedSend(
                id = UUID.randomUUID().toString(),
                text = text,
                modelId = modelId,
                attachments = attachments,
            ))
            return true
        }
        val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelId) ?: run {
            state.endGeneration(myUiToken); return false
        }
        if (providerName == Constants.PROVIDER_LOCAL) {
            val localModelId = modelId.substringAfter("${Constants.PROVIDER_LOCAL}:")
            val config = settings.localChatModels.value.find { it.modelId == localModelId }
            if (config == null || !java.io.File(config.localFilePath).exists()) {
                onSnackbar(application.getString(R.string.local_model_not_found))
                state.endGeneration(myUiToken); return false
            }
        }
        // Set loading immediately so the UI shows the sending state during attachment processing.
        state.loadingChange(myUiToken, true)

        state.generationJob = state.scope.launch {
            try {
            val myPersistId = state.nextPersistId()
            val (allImages, attachmentMeta) = payloadBuilder.buildMessagePayload(application, images, attachments)
            val currentId = genId
            executionCoordinator.withConversationLock(currentId) {
            // First user turn into a task/loop execution graduates it into the main list.
            if (convRepo.graduateConversation(currentId)) {
                onConversationGraduated(currentId)
            }
            // Apply pending per-conversation settings if any (from Advanced dialog in new chat)
            val pendingSettings = pendingConversationSettings.value
            if (pendingSettings != null) {
                settings.setConversationSettings(currentId, pendingSettings)
                pendingConversationSettings.value = null
            }
            // Resolve the conversation leaf from the DB snapshot — NOT from the global `messages`
            // flow, which reflects the currently-OPEN conversation. A background send (queued or
            // parallel) must append to ITS own conversation's leaf, otherwise it would graft onto
            // whichever conversation the user is looking at.
            val snapshotEntities = convRepo.getMessagesForConversationSnapshot(currentId)
            val selectedBeforeSend = convRepo.restoreBranchSelections(currentId)
            val path = ConversationUiState.resolvePath(
                allMessages = snapshotEntities.map {
                    ChatMessage(
                        id = it.id, parentId = it.parentId, text = it.text,
                        participant = it.participant, timestamp = it.timestamp, status = it.status,
                    )
                },
                streamingMsg = null,
                selectedChildren = selectedBeforeSend,
            )
            val lastMessageId = path.lastOrNull()?.id
            val userMessageId = UUID.randomUUID().toString()
            convRepo.upsertMessage(MessageEntity(
                id = userMessageId, conversationId = currentId, parentId = lastMessageId,
                text = text, images = allImages, thoughts = null, status = MessageStatus.SUCCESS, participant = Participant.USER, timestamp = System.currentTimeMillis(),
                attachmentMeta = attachmentMeta?.let { kotlinx.serialization.json.Json.encodeToString(it) }
            ))
            settings.incrementMessagesSent()
            val modelMessageId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis() + 1
            convRepo.upsertMessage(MessageEntity(
                id = modelMessageId, conversationId = currentId, parentId = userMessageId,
                text = "", thoughts = null, status = MessageStatus.SENDING, participant = Participant.MODEL, timestamp = startTime,
                modelName = modelId
            ))
            convRepo.getConversation(currentId)?.let { conv ->
                convRepo.upsertConversation(conv.copy(lastUpdated = System.currentTimeMillis()))
            }
            // Set streamingMessage BEFORE allMessages, so when the combine
            // re-evaluates on the allMessages change, streamingMessage is already
            // visible — eliminating the single-frame gap.
            val userMessage = ChatMessage(
                id = userMessageId, parentId = lastMessageId, text = text,
                images = allImages, participant = Participant.USER,
                status = MessageStatus.SUCCESS, timestamp = startTime - 1,
                attachmentMeta = attachmentMeta
            )
            val placeholder = ChatMessage(
                id = modelMessageId, parentId = userMessageId, text = "", participant = Participant.MODEL,
                status = MessageStatus.SENDING, timestamp = startTime, modelName = modelId
            )
            state.streamUpdate(myUiToken, placeholder)
            ifOpenOn(genId) {
                allMessages.update {
                    ConversationTurnAppend.append(it, userMessage, placeholder)
                }
            }
            val newChildren = selectedBeforeSend.toMutableMap()
            newChildren[userMessageId] = modelMessageId
            onPersistSelectedChildren(currentId, newChildren)
            ifOpenOn(genId) { selectedChildren.value = newChildren }
            ifOpenOn(genId) { onScrollToMessage(userMessageId) }

            launchGeneration(
                currentId, modelMessageId, startTime,
                isRegenerate = false, replaceMessageId = null,
                providerName, modelId, activeKey, myUiToken, myPersistId,
                state, callerTag = "sendMessage"
            )

            // Check the persisted status from the DB — allMessages.value reflects the OPEN
            // conversation, which may not be this one for a background send.
            val lastMsg = convRepo.getMessagesForConversationSnapshot(currentId).find { it.id == modelMessageId }
            if (wasNewChat && settings.titleGenerationEnabled.value && state.generationJob?.isActive == true && lastMsg?.status != MessageStatus.ERROR) {
                generateTitle(currentId)
            }
            }
        } finally {
            // Single slot owner: release the slot (token-gated — a superseded/stopped coroutine
            // no-ops) and, only if we actually released, drain the next queued send into THIS
            // conversation. Replaces the old loadingChange + sendGate.set + manual drain trio.
            releaseAndDrain(state, myUiToken, genId)
        }
        } // end launch
        return true
    }

    // ════════════════════════════════════════════════════════════════════
    // generateTitle
    // ════════════════════════════════════════════════════════════════════

    fun generateTitle(conversationId: String) {
        viewModelScope.launch {
            onSnackbarSuspend(appContext.getString(R.string.snackbar_generating_title))
            val conversation = convRepo.getConversation(conversationId) ?: return@launch
            // Resolve the TARGET conversation's own path — not messages.value, which
            // is the currently-open conversation. Otherwise a long-press "regenerate
            // title" on a background conversation would summarize the active one.
            val entities = convRepo.getMessagesForConversationSnapshot(conversationId)
            val path = ConversationUiState.resolvePath(
                allMessages = entities.map {
                    ChatMessage(
                        id = it.id,
                        parentId = it.parentId,
                        text = it.text,
                        participant = it.participant,
                        timestamp = it.timestamp,
                        status = it.status,
                        modelName = it.modelName
                    )
                },
                streamingMsg = null,
                selectedChildren = emptyMap()
            )
            val firstUserMsg = path.firstOrNull { it.participant == Participant.USER } ?: return@launch
            val firstModelMsg = path
                .filter { it.participant == Participant.MODEL && it.text.isNotBlank() }
                .firstOrNull()

            val titleModelId = settings.titleGenerationModel.value
            val modelIdWithPrefix = if (!titleModelId.isNullOrBlank()) titleModelId else (conversation.modelId ?: firstModelMsg?.modelName ?: settings.selectedModel.value)
            val modelId = ModelId.parse(modelIdWithPrefix).modelName
            val (providerName, activeKey) = requestBuilder.resolveProviderKey(modelIdWithPrefix) ?: return@launch

            val summaryText = if (firstModelMsg != null) {
                "User: ${firstUserMsg.text}\nAssistant: ${firstModelMsg.text.take(500)}"
            } else {
                firstUserMsg.text
            }

            val titlePrompt = listOf(
                ChatMessage(
                    text = "Generate a short title (5 words maximum) for this conversation:\n\n$summaryText\n\nRespond with ONLY the title text, no quotes, no punctuation, no explanation.",
                    participant = Participant.USER,
                    status = MessageStatus.SUCCESS
                )
            )

            val provider = providerRegistry.getInstance(providerName)
            val config = ProviderConfig(
                apiKey = activeKey,
                modelId = modelId,
                systemPrompt = settings.titleGenerationPrompt.value.ifBlank { BuiltInPrompts.TITLE_GENERATION_SYSTEM },
                maxContextWindow = 1,
                thinkingEnabled = false,
                baseUrl = providerRegistry.getEffectiveBaseUrl(providerName)
            )

            var title = ""
            try {
                // Title generation is a real provider call. Remote title gen runs without any
                // global slot (it's cheap and independent); local title gen takes the shared
                // LocalModelSerializer mutex so it can't load a model alongside an in-flight
                // chat/embedding turn (OOM). The mutex is fair and cancellable, so a Stop or a
                // new send isn't blocked behind it.
                if (providerName == Constants.PROVIDER_LOCAL) {
                    com.newoether.agora.api.LocalModelSerializer.mutex.withLock {
                        withContext(Dispatchers.IO) {
                            provider.generateResponse(titlePrompt, config).collect { event ->
                                if (event is StreamEvent.TextChunk) title += event.text
                                else if (event is StreamEvent.Error) DebugLog.e("AgoraVM", "Title generation error: ${event.message}")
                            }
                        }
                        // Intentionally do NOT releaseEngine() here. Title generation runs right
                        // after the first message of a new conversation, on the same model the
                        // user is actively chatting with; LocalProvider.ensureEngineLoaded reuses
                        // the already-loaded engine. Releasing here would force the next message
                        // to re-load a multi-GB model onto a possibly-fragmented native heap,
                        // which is the leading suspect for the "second message OOM" crash (#53,
                        // 31 OOM reports) — the text path itself is crash-safe. Keeping the
                        // engine session-scoped (released only on model switch / RAG / process
                        // death) eliminates that reload churn. This is an OOM-probability
                        // reduction, NOT a claimed #53 root-case fix (that needs a logcat).
                    }
                } else {
                    provider.generateResponse(titlePrompt, config).collect { event ->
                        if (event is StreamEvent.TextChunk) title += event.text
                        else if (event is StreamEvent.Error) DebugLog.e("AgoraVM", "Title generation error: ${event.message}")
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraVM", "Title generation failed for provider=$providerName model=$modelId", e)
                return@launch
            }

            title = title.trim().replace("\n", " ").take(60)
            if (title.isNotBlank()) {
                convRepo.getConversation(conversationId)?.let { existing ->
                    convRepo.upsertConversation(existing.copy(title = title))
                }
                onSnackbarSuspend(appContext.getString(R.string.snackbar_title_generated))
            } else {
                onSnackbarSuspend(appContext.getString(R.string.snackbar_title_error))
            }
        }
    }
}
