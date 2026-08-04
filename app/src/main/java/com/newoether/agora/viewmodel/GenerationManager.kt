package com.newoether.agora.viewmodel

import android.app.Application
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.data.MemoryManager

import com.newoether.agora.data.local.MessageEntity
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessagePersistenceGuard
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.Participant
import com.newoether.agora.model.ToolCallData
import com.newoether.agora.R
import com.newoether.agora.service.AgoraForegroundService
import com.newoether.agora.service.AppForegroundTracker
import com.newoether.agora.api.util.projectAssistantImagesToLatestUserMessage
import com.newoether.agora.util.Constants
import com.newoether.agora.util.SearchResultFormatter
import com.newoether.agora.tool.ImageGenToolProvider
import com.newoether.agora.tool.MemoryToolProvider
import com.newoether.agora.tool.RagToolProvider
import com.newoether.agora.tool.ShellToolProvider
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.tool.WebSearchToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Never route a request through an arbitrary fallback provider. */
internal fun <T> requireRegisteredProvider(providers: Map<String, T>, name: String): T =
    requireNotNull(providers[name]) { "Provider is not registered: $name" }

data class GenerationConfig(
    val providerName: String,
    val modelId: String,
    val apiKey: String,
    val effectiveSystemPrompt: String?,
    val maxContextWindow: Int,
    val codeExecutionEnabled: Boolean,
    val googleSearchEnabled: Boolean,
    val thinkingEnabled: Boolean,
    val thinkingLevel: String = "medium",
    val thinkingBudgetEnabled: Boolean = false,
    val thinkingBudgetTokens: Int = 4096,
    val baseUrl: String?,
    val userPrepend: String? = null,
    val userPostpend: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null
)

data class GenerationContext(
    val conversationId: String? = null,
    val accessSavedMemories: Boolean = true,
    val accessActiveMemory: Boolean = true,
    val accessPastConversations: Boolean = true,
    val modelSearchMethod: String = "keyword",
    val activeEmbeddingConfig: com.newoether.agora.data.EmbeddingModelConfig? = null,
    val embeddingApiKey: String = "",
    val ragThreshold: Float = 0.5f,
    val searchMatchLimit: Int = 10,
    val searchContextWindow: Int = 8,
    val webSearchEnabled: Boolean = false,
    val webSearchApiKeys: Map<String, String> = emptyMap(),
    val webSearchProvider: String = "duckduckgo",
    val webSearchNumResults: Int = 5,
    val webSearchBaseUrl: String = "",
    val imageGenEnabled: Boolean = false,
    val imageGenApiKey: String = "",
    val imageGenBaseUrl: String = "",
    val imageGenModel: String = "gpt-image-1",
    val imageGenSize: String = "1024x1024",
    val automationToolsEnabled: Boolean = false,
    /** Workers use WorkManager's foreground execution instead of starting our service. */
    val foregroundServiceManagedExternally: Boolean = false,
    val shellEnabled: Boolean = false,
    val shellDevices: List<com.newoether.agora.data.ShellDeviceConfig> = emptyList(),
    val sandboxEnabled: Boolean = false,
    val imageTranscriptionEnabled: Boolean = false,
    val imageTranscriptionModel: String? = null,
    val imageTranscriptionBatchSize: Int = 3,
    val imageTranscriptionPrompt: String = com.newoether.agora.data.BuiltInPrompts.IMAGE_TRANSCRIPTION_USER,
    val transcriptionProviderName: String = "",
    val transcriptionModelId: String = "",
    val transcriptionApiKey: String = "",
    val transcriptionBaseUrl: String? = null,
    /** Wall-clock budget for a single tool execution; downgrades a blocking tool from a
     *  permanent generation hang to a recoverable tool error (#49). */
    val toolTimeoutMs: Long = Constants.TOOL_EXECUTION_TIMEOUT_MS
)

internal fun applyUserTemplateToMessages(
    messages: List<ChatMessage>,
    prepend: String?,
    postpend: String?
): List<ChatMessage> {
    if (prepend == null && postpend == null) return messages
    val timeSdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    return messages.map { msg ->
        val isToolMessage = msg.id.startsWith(Constants.TOOL_MSG_PREFIX) ||
            msg.id.startsWith(Constants.RESULT_MSG_PREFIX)
        if (!isToolMessage && msg.participant == Participant.USER && msg.text.isNotEmpty()) {
            val ts = java.util.Date(msg.timestamp)
            val rp = prepend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
            val ra = postpend?.replace("{sent_time}", timeSdf.format(ts))?.replace("{sent_date}", dateSdf.format(ts)) ?: ""
            if (rp.isEmpty() && ra.isEmpty()) msg
            else msg.copy(text = rp + msg.text + ra)
        } else msg
    }
}

/**
 * The token-gated UI callbacks a single generation drives. Built once per call by
 * [ConversationGenerationState.callbacksFor], so each generation entry point
 * ([MessageGenerationController]'s send / regenerate / edit) wires the per-conversation
 * ownership tokens in exactly one place instead of re-threading lambdas by hand.
 *
 * Note: the generation-slot lifecycle (generating flag / active-conversation set) is owned by
 * [MessageGenerationController] via [ConversationGenerationState.acquireForSend] /
 * [ConversationGenerationState.tryAcquireForReplacement] / [ConversationGenerationState.endGeneration],
 * NOT by these callbacks — GenerationManager only streams tokens into the message and persists the
 * terminal DB row.
 */
data class GenerationCallbacks(
    val onStreamUpdate: (ChatMessage) -> Unit,
    val onLoadingChange: (Boolean) -> Unit,
    val onStreamClear: () -> Unit,
    val isLatestPersist: () -> Boolean,
)

class GenerationManager(
    private val app: Application,
    private val conversations: com.newoether.agora.data.repository.ConversationRepository,
    private val memoryManager: MemoryManager,
    private val providers: Map<String, LlmProvider>,
    private val context: android.content.Context,
    private val sandboxFactory: com.newoether.agora.sandbox.SandboxManagerFactory? = null,
    additionalToolProviders: List<ToolProvider> = emptyList(),
) {
    var onMessagePersisted: ((messageId: String, text: String) -> Unit)? = null

    /** User-confirmation gate for remote shell mutations. Set by the ViewModel.
     *  Returns true to proceed, false to deny. */
    var onConfirmShellCommand: (suspend (server: String, summary: String) -> Boolean)? = null

    /** User-confirmation gate for every GitHub mutation. Null fails closed. */
    var onConfirmGitHubAction: (suspend (repository: String, summary: String) -> Boolean)? = null

    private val memoryToolProvider = MemoryToolProvider(memoryManager)
    private val webSearchToolProvider = WebSearchToolProvider()
    private val ragToolProvider = RagToolProvider(conversations)
    private val imageGenToolProvider = ImageGenToolProvider(app)
    private val githubToolProvider = com.newoether.agora.tool.GitHubToolProvider(app).also { gtp ->
        gtp.confirm = { repository, summary ->
            onConfirmGitHubAction?.invoke(repository, summary) ?: false
        }
    }
    private val githubWatchToolProvider = com.newoether.agora.tool.GitHubWatchToolProvider(app)
    private val githubPullRequestToolProvider = com.newoether.agora.tool.GitHubPullRequestToolProvider(app).also { prp ->
        prp.confirm = { repository, summary ->
            onConfirmGitHubAction?.invoke(repository, summary) ?: false
        }
    }
    private val githubCloneToolProvider = com.newoether.agora.tool.GitHubCloneToolProvider(app, sandboxFactory)
    private val umaToolProvider = com.newoether.agora.tool.UmaToolProvider()
    private val shellToolProvider = ShellToolProvider(sandboxFactory).also { stp ->
        // Forward to the ViewModel-provided gate at call time (read the var lazily).
        stp.confirm = { server, summary -> onConfirmShellCommand?.invoke(server, summary) ?: true }
    }
    private val builtInToolProviders: List<ToolProvider> = listOf(
        memoryToolProvider, webSearchToolProvider, ragToolProvider, imageGenToolProvider,
        githubToolProvider, githubWatchToolProvider, githubPullRequestToolProvider,
        githubCloneToolProvider, umaToolProvider, shellToolProvider
    )
    private val toolProviders: List<ToolProvider> = builtInToolProviders + additionalToolProviders

    fun buildImageGenTool(ctx: GenerationContext): List<ToolDefinition> =
        imageGenToolProvider.definitions(ctx)

    private val transcriptionManager = TranscriptionManager(providers, conversations, context)

    companion object {
        private val FILE_TOOL_NAMES = setOf("file_read", "file_write", "file_edit", "file_glob", "file_grep")
    }

    private fun getProviderInstance(name: String): LlmProvider =
        requireRegisteredProvider(providers, name)

    // Image/video frame extraction lives in ImageProcessor (single source of truth).
    private val imageProcessor = ImageProcessor(app)

    suspend fun processImages(
        uris: List<String>,
        sliceConfigs: Map<String, VideoSliceConfig> = emptyMap()
    ): List<String> = imageProcessor.processImagesAndVideos(uris, sliceConfigs)

    fun buildMemoryTools(ctx: GenerationContext): List<ToolDefinition> =
        memoryToolProvider.definitions(ctx)

    fun buildWebSearchTool(ctx: GenerationContext): List<ToolDefinition> =
        webSearchToolProvider.definitions(ctx)

    fun buildRagTool(ctx: GenerationContext): List<ToolDefinition> =
        ragToolProvider.definitions(ctx)

    fun buildShellTool(ctx: GenerationContext): List<ToolDefinition> {
        val all = shellToolProvider.definitions(ctx)
        return all.filter { it.function.name !in FILE_TOOL_NAMES }
    }

    fun buildFileTool(ctx: GenerationContext): List<ToolDefinition> {
        val all = shellToolProvider.definitions(ctx)
        return all.filter { it.function.name in FILE_TOOL_NAMES }
    }


    /** Semantic message search — delegates to [RagToolProvider], which owns the
     *  embedding-search logic. Kept here as the entry point used by ChatViewModel's
     *  in-app conversation search. */
    suspend fun semanticSearch(query: String, limit: Int, ctx: GenerationContext): List<Pair<MessageEntity, Float>> =
        ragToolProvider.semanticSearch(query, limit, ctx)

    private suspend fun executeTool(name: String, arguments: String, ctx: GenerationContext): String {
        return try {
            for (provider in toolProviders) {
                if (provider.handles(name)) {
                    // Tools run inline on the stream-consuming coroutine, so a tool that blocks
                    // forever hangs the whole generation. Bound it; on timeout return a tool error
                    // so the tool loop continues instead of hanging (#49).
                    return withTimeout(ctx.toolTimeoutMs) {
                        provider.execute(name, arguments, ctx)
                    }
                }
            }
            "Unknown tool: $name"
        } catch (e: TimeoutCancellationException) {
            // A timeout is a recoverable tool failure, NOT a generation cancellation — return an
            // error string so the model can react, instead of unwinding the whole generation.
            "Error executing tool '$name': timed out after ${ctx.toolTimeoutMs}ms"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.localizedMessage ?: "Unknown error"}"
        }
    }

    private fun applyUserTemplate(messages: List<ChatMessage>, prepend: String?, postpend: String?): List<ChatMessage> {
        return applyUserTemplateToMessages(messages, prepend, postpend)
    }

    private fun appendMergedSegment(target: MutableList<MessageSegment>, segment: MessageSegment) {
        val last = target.lastOrNull()
        if (last != null && last.type == segment.type && (segment.type == "answer" || segment.type == "thought")) {
            target[target.lastIndex] = last.copy(
                content = last.content + segment.content,
                signature = segment.signature ?: last.signature,
                durationMs = mergeDurationMs(last.durationMs, segment.durationMs)
            )
        } else {
            target.add(segment)
        }
    }

    private fun mergeDurationMs(first: Long?, second: Long?): Long? {
        val merged = (first ?: 0L) + (second ?: 0L)
        return merged.takeIf { it > 0L }
    }

    private fun buildLiveSegments(
        flushed: List<MessageSegment>,
        answerBuf: StringBuilder,
        thoughtBuf: StringBuilder,
        signature: String? = null,
        thoughtDurationMs: Long? = null
    ): List<MessageSegment>? {
        val result = flushed.toMutableList()
        if (answerBuf.isNotEmpty()) {
            appendMergedSegment(result, MessageSegment(type = "answer", content = answerBuf.toString()))
        }
        if (thoughtBuf.isNotEmpty()) {
            appendMergedSegment(result, MessageSegment(
                type = "thought",
                content = thoughtBuf.toString(),
                signature = signature,
                durationMs = thoughtDurationMs
            ))
        }
        return result.ifEmpty { null }
    }

    private suspend fun buildApiPath(
        parentId: String?,
        conversationId: String,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        config: GenerationConfig,
        ctx: GenerationContext
    ): Pair<List<ChatMessage>, ProviderConfig> {
        val dbMessages = conversations.getMessagesForConversationSnapshot(conversationId)
        val pathEntities = mutableListOf<MessageEntity>()
        var currId: String? = parentId
        while (currId != null) {
            val msg = dbMessages.find { it.id == currId } ?: break
            pathEntities.add(0, msg)
            currId = msg.parentId
        }
        // Inject tool call chains that are children of messages in the ancestor path.
        val expanded = mutableListOf<MessageEntity>()
        for (entity in pathEntities) {
            val toolChildren = dbMessages
                .filter { it.parentId == entity.id && it.id.startsWith(Constants.TOOL_MSG_PREFIX) }
                .sortedBy { it.timestamp }
            if (toolChildren.isEmpty()) {
                expanded.add(entity)
            } else {
                for (toolMsg in toolChildren) {
                    expanded.add(toolMsg)
                    val pending = mutableListOf(toolMsg)
                    var safety = 0
                    while (pending.isNotEmpty() && safety < 100) {
                        val current = pending.removeAt(0)
                        val children = dbMessages
                            .filter { it.parentId == current.id && (it.id.startsWith(Constants.RESULT_MSG_PREFIX) || it.id.startsWith(Constants.TOOL_MSG_PREFIX)) }
                            .sortedBy { it.timestamp }
                        for (child in children) {
                            val isResult = child.id.startsWith(Constants.RESULT_MSG_PREFIX)
                            if (isResult) {
                                // Include result_ messages so providers can emit
                                // correct tool_use/tool_result pairs. The result
                                // data lives in TOOL_MSG segments too, but Anthropic
                                // requires separate tool_result blocks in the next
                                // user-role message.
                                if (child !in expanded) {
                                    expanded.add(child)
                                }
                                pending.add(child)
                            } else if (child !in expanded) {
                                expanded.add(child)
                                pending.add(child)
                            }
                        }
                        safety++
                    }
                }
                expanded.add(entity.copy(toolCallJson = null))
            }
        }
        val currentPath = expanded.map {
            val segs = it.toolCallJson?.let { json -> try { Json.decodeFromString<List<MessageSegment>>(json) } catch (_: Exception) { null } }
            val toolCall = segs?.lastOrNull { s -> s.type == "tool" }?.let { s ->
                ToolCallData(s.toolName ?: "", s.toolArgs ?: "{}", s.toolResult ?: "", s.toolCallId)
            }
            val meta = it.attachmentMeta?.let { json -> try { Json.decodeFromString<com.newoether.agora.model.AttachmentMeta>(json) } catch (_: Exception) { null } }
            val attachmentText = if (meta != null) {
                meta.items.mapNotNull { item ->
                    val content = item.textContent
                    val transcription = item.transcription
                    val includeTranscription = ctx.imageTranscriptionEnabled && transcription != null && transcription.isNotBlank()
                    when {
                        content != null -> {
                            val label = item.fileName ?: "file"
                            "\n\n--- File: $label ---\n$content"
                        }
                        includeTranscription -> {
                            val label = item.fileName ?: "image"
                            "\n\n--- Image Transcription: $label ---\n$transcription"
                        }
                        else -> null
                    }
                }.joinToString("")
            } else ""
            val combinedText = if (attachmentText.isNotBlank()) it.text + attachmentText else it.text
            val hasTranscription = ctx.imageTranscriptionEnabled && meta != null && meta.items.any { item -> !item.transcription.isNullOrBlank() }
            val effectiveImages = if (hasTranscription) emptyList() else it.images
            ChatMessage(id = it.id, parentId = it.parentId, text = combinedText, images = effectiveImages, thoughts = it.thoughts, thoughtTitle = it.thoughtTitle, tokenCount = it.tokenCount, status = it.status, participant = it.participant, timestamp = it.timestamp, thoughtTimeMs = it.thoughtTimeMs, segments = segs, toolCall = toolCall)
        }.filter { it.participant != Participant.ERROR }
            .let { path ->
                if (isRegenerate && replaceMessageId != null) {
                    val oldIdx = path.indexOfFirst { it.id == replaceMessageId }
                    if (oldIdx >= 0) path.take(oldIdx) else path
                } else path
            }

        val allTools = toolProviders.flatMap { it.definitions(ctx) }
        val providerConfig = ProviderConfig(
            apiKey = config.apiKey,
            modelId = config.modelId,
            systemPrompt = config.effectiveSystemPrompt,
            maxContextWindow = config.maxContextWindow,
            codeExecutionEnabled = config.codeExecutionEnabled,
            googleSearchEnabled = config.googleSearchEnabled,
            thinkingEnabled = config.thinkingEnabled,
            thinkingLevel = config.thinkingLevel,
            thinkingBudgetEnabled = config.thinkingBudgetEnabled,
            thinkingBudgetTokens = config.thinkingBudgetTokens,
            baseUrl = config.baseUrl,
            tools = allTools,
            userPrepend = config.userPrepend,
            userPostpend = config.userPostpend,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        )
        return Pair(currentPath, providerConfig)
    }

    suspend fun generate(
        conversationId: String,
        modelMessageId: String,
        startTime: Long,
        isRegenerate: Boolean,
        replaceMessageId: String?,
        modelName: String,
        config: GenerationConfig,
        ctx: GenerationContext,
        generationJob: kotlinx.coroutines.Job?,
        callbacks: GenerationCallbacks,
        streamScope: StreamScope? = null,
    ) = com.newoether.agora.api.HttpClient.withStreamScope(streamScope) {
        // Bind every provider/tool stream opened by this generation to its coroutine-local
        // StreamScope. Parallel conversations therefore cannot overwrite one another's Stop
        // ownership, while child dispatcher hops inherit the same context element.
        // Destructure into locals so the body below reads exactly as before.
        val (onStreamUpdate, onLoadingChange, onStreamClear, isLatestPersist) = callbacks

        var foregroundLeaseAcquired = false
        var totalText = ""
        var totalThoughts = ""
        var thinkingPlaceholder = ""
        var totalThoughtTitle: String? = null
        var totalTokenCount = 0
        var totalThoughtTimeMs: Long? = null
        var cumulativeThoughtMs: Long = 0
        var currentThoughtStartMs: Long? = null
        var currentThoughtDurationMs: Long = 0
        var currentStatus = MessageStatus.SENDING
        var retryText: String? = null
        val segments = mutableListOf(MessageSegment(type = "answer"))
        val generatedImages = mutableListOf<String>()
        var currentAnswerBuf = StringBuilder()
        var currentThoughtBuf = StringBuilder()
        var currentThoughtSignature: String? = null
        var parentId: String? = null
        var toolPath = emptyList<ChatMessage>()

        fun liveThoughtDurationMs(): Long? {
            val liveElapsed = currentThoughtStartMs?.let { System.currentTimeMillis() - it } ?: 0L
            return (currentThoughtDurationMs + liveElapsed).takeIf { it > 0L }
        }

        fun finishCurrentThoughtTiming() {
            val startedAt = currentThoughtStartMs ?: return
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > 0L) {
                cumulativeThoughtMs += elapsed
                currentThoughtDurationMs += elapsed
                totalThoughtTimeMs = cumulativeThoughtMs
            }
            currentThoughtStartMs = null
        }

        try {
            val provider = getProviderInstance(config.providerName)
            onLoadingChange(true)
            // Slot ownership (generating flag / active set) is claimed synchronously by the
            // controller before this coroutine runs — GenerationManager no longer touches it.
            com.newoether.agora.util.CrashReporter.note("generate provider=${config.providerName} regen=$isRegenerate")
            thinkingPlaceholder = context.getString(R.string.thinking_ellipsis)
            val placeholder = conversations.getMessagesForConversationSnapshot(conversationId)
                .find { it.id == modelMessageId }
            parentId = placeholder?.parentId
            if (!ctx.foregroundServiceManagedExternally) {
                foregroundLeaseAcquired = withContext(Dispatchers.Main) {
                    AgoraForegroundService.acquire(app, modelMessageId)
                }
            }

            // Stage 1: Image Transcription
            var transcriptionPerformed = false
            if (ctx.imageTranscriptionEnabled && ctx.transcriptionModelId.isNotEmpty()) {
                kotlinx.coroutines.delay(500) // let foreground service fully start
                val targets = transcriptionManager.collectTargets(conversationId, parentId)
                if (targets.isNotEmpty()) {
                    val (transcriptionSegments, transcriptionError) = transcriptionManager.transcribe(
                        targets, conversationId,
                        ctx.transcriptionProviderName, ctx.transcriptionModelId,
                        ctx.transcriptionApiKey, ctx.transcriptionBaseUrl,
                        ctx.imageTranscriptionPrompt,
                        generationJob, modelMessageId, startTime, onStreamUpdate
                    )
                    if (transcriptionError != null) {
                        totalText = transcriptionError
                        currentStatus = MessageStatus.ERROR
                        transcriptionPerformed = true
                    } else {
                        segments.addAll(0, transcriptionSegments)
                        transcriptionPerformed = true
                    }
                }
            }

            if (currentStatus != MessageStatus.ERROR) {
            val (currentPath, rawProviderConfig) = buildApiPath(parentId, conversationId, isRegenerate, replaceMessageId, config, ctx)
            val providerConfig = if (transcriptionPerformed) rawProviderConfig.copy(includeImages = false) else rawProviderConfig

            var toolCallData: ToolCallData? = null
            var toolCallDataList: List<ToolCallData> = emptyList()
            val roundToolSegments = mutableListOf<MessageSegment>()

            var lastEmitMs = 0L
            var lastCheckpointMs = 0L

            fun modelMessage() = ChatMessage(
                id = modelMessageId, parentId = parentId,
                text = totalText, thoughts = totalThoughts.ifBlank { null },
                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                status = currentStatus, participant = Participant.MODEL,
                timestamp = startTime, thoughtTimeMs = totalThoughtTimeMs,
                modelName = modelName, toolCall = toolCallData,
                images = generatedImages.toList(),
                segments = buildLiveSegments(
                    segments,
                    currentAnswerBuf,
                    currentThoughtBuf,
                    currentThoughtSignature,
                    liveThoughtDurationMs()
                ),
                retryText = retryText
            )

            fun flushAnswerSegment() {
                if (currentAnswerBuf.isNotEmpty()) {
                    appendMergedSegment(segments, MessageSegment(type = "answer", content = currentAnswerBuf.toString()))
                    currentAnswerBuf = StringBuilder()
                }
            }

            fun flushThoughtSegment() {
                finishCurrentThoughtTiming()
                if (currentThoughtBuf.isNotEmpty()) {
                    appendMergedSegment(segments, MessageSegment(
                        type = "thought",
                        content = currentThoughtBuf.toString(),
                        signature = currentThoughtSignature,
                        durationMs = currentThoughtDurationMs.takeIf { it > 0L }
                    ))
                    currentThoughtBuf = StringBuilder()
                    currentThoughtSignature = null
                }
                currentThoughtDurationMs = 0L
            }

            suspend fun checkpointStreamingMessage(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastCheckpointMs < 2_000L) return
                if (!isLatestPersist()) return
                if (conversations.getConversation(conversationId) == null) return
                val liveSegments = buildLiveSegments(
                    segments,
                    currentAnswerBuf,
                    currentThoughtBuf,
                    currentThoughtSignature,
                    liveThoughtDurationMs()
                )
                conversations.upsertMessage(MessageEntity(
                    id = modelMessageId,
                    conversationId = conversationId,
                    parentId = parentId,
                    text = totalText,
                    images = generatedImages.toList(),
                    thoughts = totalThoughts.ifBlank { null },
                    thoughtTitle = totalThoughtTitle,
                    tokenCount = totalTokenCount,
                    status = currentStatus,
                    participant = Participant.MODEL,
                    timestamp = startTime,
                    thoughtTimeMs = totalThoughtTimeMs,
                    modelName = modelName,
                    toolCallJson = MessagePersistenceGuard.encodeSegmentsBounded(liveSegments),
                ))
                lastCheckpointMs = now
            }

            suspend fun handleStreamEvent(event: StreamEvent) {
                when (event) {
                    is StreamEvent.TextChunk -> {
                        val answerText = if (currentStatus == MessageStatus.THINKING) event.text.trimStart() else event.text
                        if (currentStatus == MessageStatus.THINKING && answerText.isBlank()) {
                            retryText = null
                            return
                        }
                        if (currentStatus == MessageStatus.THINKING) {
                            flushThoughtSegment()
                        }
                        totalText += answerText
                        currentAnswerBuf.append(answerText)
                        if (answerText.isNotBlank()) {
                            currentStatus = MessageStatus.SENDING
                        }
                        retryText = null
                    }
                    is StreamEvent.ThoughtChunk -> {
                        flushAnswerSegment()
                        currentStatus = MessageStatus.THINKING
                        retryText = null
                        if (currentThoughtStartMs == null) {
                            currentThoughtStartMs = System.currentTimeMillis()
                        }
                        if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        if (event.thought.isNotEmpty()) {
                            currentThoughtBuf.append(event.thought)
                            if (totalThoughts == thinkingPlaceholder) totalThoughts = event.thought
                            else totalThoughts += event.thought
                        }
                        if (event.title != null) totalThoughtTitle = event.title
                        if (event.signature != null) currentThoughtSignature = event.signature
                    }
                    is StreamEvent.UsageUpdate -> {
                        if (event.tokenCount > 0) totalTokenCount = event.tokenCount
                        if (totalText.isEmpty() && event.thoughtsTokenCount > 0) {
                            currentStatus = MessageStatus.THINKING
                            if (currentThoughtStartMs == null) {
                                currentThoughtStartMs = System.currentTimeMillis()
                            }
                            if (totalThoughts.isEmpty()) totalThoughts = thinkingPlaceholder
                        }
                    }
                    is StreamEvent.Retrying -> {
                        retryText = context.getString(R.string.generation_retry_attempt, event.attempt, event.maxAttempts)
                        onStreamUpdate(modelMessage())
                    }
                    is StreamEvent.Error -> {
                        flushThoughtSegment()
                        retryText = null
                        if (toolCallData == null && toolCallDataList.isEmpty()) {
                            totalText = event.message
                            currentStatus = MessageStatus.ERROR
                        }
                    }
                    is StreamEvent.ToolCallRequest -> {
                        flushAnswerSegment()
                        flushThoughtSegment()
                        val ts = MessageSegment(type = "tool", toolName = event.name, toolArgs = event.arguments, toolResult = null, toolCallId = event.id, signature = event.signature)
                        appendMergedSegment(segments, ts)
                        currentStatus = MessageStatus.TOOL_CALLING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                        val result = executeTool(event.name, event.arguments, ctx)
                        generatedImages.addAll(imageGenToolProvider.drainImages(conversationId))
                        val clipped = result.take(Constants.MAX_TOOL_RESULT_LENGTH)
                        val idx = segments.indexOfLast { it.toolCallId == event.id }
                        if (idx >= 0) {
                            segments[idx] = segments[idx].copy(toolResult = clipped)
                            roundToolSegments.add(segments[idx])
                        }
                        val tcd = ToolCallData(event.name, event.arguments, clipped, event.signature, event.id)
                        if (toolCallData == null) toolCallData = tcd
                        toolCallDataList = toolCallDataList + tcd
                        currentStatus = MessageStatus.SENDING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                    }
                    is StreamEvent.ToolCallsRequest -> {
                        flushAnswerSegment()
                        flushThoughtSegment()
                        event.calls.forEach { call ->
                            appendMergedSegment(segments, MessageSegment(type = "tool", toolName = call.name, toolArgs = call.arguments, toolResult = null, toolCallId = call.id, signature = call.signature))
                        }
                        currentStatus = MessageStatus.TOOL_CALLING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                        val tcds = event.calls.map { call ->
                            val result = executeTool(call.name, call.arguments, ctx)
                            generatedImages.addAll(imageGenToolProvider.drainImages(conversationId))
                            val clipped = result.take(Constants.MAX_TOOL_RESULT_LENGTH)
                            val idx = segments.indexOfLast { it.toolCallId == call.id }
                            if (idx >= 0) {
                                segments[idx] = segments[idx].copy(toolResult = clipped)
                                roundToolSegments.add(segments[idx])
                            }
                            ToolCallData(call.name, call.arguments, clipped, call.signature, call.id)
                        }
                        toolCallData = tcds.firstOrNull()
                        toolCallDataList = tcds
                        currentStatus = MessageStatus.SENDING
                        onStreamUpdate(modelMessage())
                        lastEmitMs = System.currentTimeMillis()
                    }
                }

                val now = System.currentTimeMillis()
                val isSignificant = event is StreamEvent.Error
                if (now - lastEmitMs >= 500 || isSignificant) {
                    onStreamUpdate(modelMessage())
                    lastEmitMs = now
                }
                // Durable progress belongs to the originating conversation even while it is not
                // open. A process death or collector switch can therefore recover recent text
                // instead of the original empty SENDING placeholder.
                checkpointStreamingMessage(force = isSignificant)
            }

            val projectedPath = projectAssistantImagesToLatestUserMessage(currentPath, providerConfig.includeImages)
            val apiPath = applyUserTemplate(projectedPath, config.userPrepend, config.userPostpend)
            provider.generateResponse(apiPath, providerConfig).collect { event ->
                handleStreamEvent(event)
            }
            finishCurrentThoughtTiming()
            // Always emit final state after collection completes
            if (generationJob?.isCancelled != true) {
                onStreamUpdate(modelMessage())
            }

            // Multi-tool loop
            var toolRound = 0
            toolPath = currentPath

            while (toolCallDataList.isNotEmpty() && currentStatus != MessageStatus.ERROR && currentCoroutineContext().isActive) {
                toolRound++
                val roundToolList = roundToolSegments.toList()
                roundToolSegments.clear()
                val thoughtSegs = segments.filter { it.type == "thought" }
                val txedSegments = if (thoughtSegs.isNotEmpty()) thoughtSegs + roundToolList else roundToolList
                val prevLastId = if (toolRound == 1) modelMessageId else toolPath.lastOrNull()?.id
                val toolMsgId = "${Constants.TOOL_MSG_PREFIX}${UUID.randomUUID()}"
                val toolMsgSegs = txedSegments.ifEmpty { null }
                val tcds = toolCallDataList
                val allSegments = toolMsgSegs ?: tcds.map { tc ->
                    MessageSegment(type = "tool", toolName = tc.toolName, toolArgs = tc.arguments, toolResult = tc.result, signature = tc.signature, toolCallId = tc.toolCallId)
                }
                // Bound the aggregate: a model message row crams every tool round into one
                // toolCallJson column, so many rounds × a clipped 100KB result can still exceed the
                // 2MB CursorWindow. The guard halves the largest results until it fits (#51).
                val allSegmentsJson = MessagePersistenceGuard.encodeSegmentsBounded(allSegments)
                val resultMsgs = tcds.map { tcData ->
                    val rid = "${Constants.RESULT_MSG_PREFIX}${UUID.randomUUID()}"
                    val displayText = SearchResultFormatter.format(tcData.result, context)
                    rid to ChatMessage(
                        id = rid, parentId = toolMsgId,
                        text = displayText,
                        participant = Participant.USER, status = MessageStatus.SUCCESS,
                        toolCall = tcData
                    )
                }
                toolPath = toolPath.toMutableList().apply {
                    add(ChatMessage(
                        id = toolMsgId, parentId = prevLastId,
                        text = "", participant = Participant.MODEL,
                        status = MessageStatus.SUCCESS, toolCall = tcds.first(),
                        segments = toolMsgSegs
                    ))
                    for ((_, msg) in resultMsgs) add(msg)
                }
                conversations.upsertMessage(MessageEntity(
                    id = toolMsgId, conversationId = conversationId, parentId = prevLastId,
                    text = "", thoughts = null, status = MessageStatus.SUCCESS,
                    participant = Participant.MODEL, timestamp = System.currentTimeMillis(),
                    toolCallJson = allSegmentsJson
                ))
                for ((index, entry) in resultMsgs.withIndex()) {
                    val (rid, _) = entry
                    conversations.upsertMessage(MessageEntity(
                        id = rid, conversationId = conversationId, parentId = toolMsgId,
                        text = tcds[index].result, thoughts = null, status = MessageStatus.SUCCESS,
                        participant = Participant.USER, timestamp = System.currentTimeMillis(),
                        toolCallJson = Json.encodeToString(listOf(
                            MessageSegment(type = "tool", toolName = tcds[index].toolName, toolArgs = tcds[index].arguments, toolResult = tcds[index].result, signature = tcds[index].signature, toolCallId = tcds[index].toolCallId)
                        ))
                    ))
                }

                toolCallData = null
                toolCallDataList = emptyList()

                lastEmitMs = 0L

                val projectedToolPath = projectAssistantImagesToLatestUserMessage(toolPath, providerConfig.includeImages)
                val apiToolPath = applyUserTemplate(projectedToolPath, config.userPrepend, config.userPostpend)
                provider.generateResponse(apiToolPath, providerConfig).collect { event ->
                    handleStreamEvent(event)
                }
                finishCurrentThoughtTiming()
                // Always emit final state after tool round completes
                onStreamUpdate(modelMessage())
            }

            if (!currentCoroutineContext().isActive) {
                currentStatus = MessageStatus.STOPPED
            }

            if (!isRegenerate && isLatestPersist()) for (msg in toolPath) {
                if (msg.id.startsWith(Constants.TOOL_MSG_PREFIX) || msg.id.startsWith(Constants.RESULT_MSG_PREFIX)) {
                    val exists = conversations.getMessagesForConversationSnapshot(conversationId).any { it.id == msg.id }
                    if (!exists) {
                        conversations.upsertMessage(MessageEntity(
                            id = msg.id, conversationId = conversationId, parentId = msg.parentId,
                            text = msg.text, thoughts = null, status = msg.status,
                            participant = msg.participant, timestamp = System.currentTimeMillis(),
                            toolCallJson = msg.segments?.let { MessagePersistenceGuard.encodeSegmentsBounded(it) }
                                ?: msg.toolCall?.let { Json.encodeToString(listOf(
                                    MessageSegment(type = "tool", toolName = it.toolName, toolArgs = it.arguments, toolResult = it.result, signature = it.signature, toolCallId = it.toolCallId)
                                )) }
                        ))
                    }
                }
            }

            if (currentStatus != MessageStatus.ERROR) {
                currentStatus = if (totalText.isNotEmpty() || totalThoughts.isNotEmpty()) MessageStatus.SUCCESS else MessageStatus.ERROR
            }
            if (generationJob?.isCancelled == true && currentStatus != MessageStatus.ERROR) {
                currentStatus = MessageStatus.STOPPED
            }
            } // else { // called buildApiPath when currentStatus == ERROR
        } catch (e: CancellationException) {
            currentStatus = MessageStatus.STOPPED
            throw e
        } catch (e: Exception) {
            val isCancelled = generationJob?.isCancelled == true
            currentStatus = if (isCancelled) MessageStatus.STOPPED else MessageStatus.ERROR
            if (!isCancelled) {
                totalText = "Error: ${e.localizedMessage ?: "An unexpected error occurred."}"
            }
        } finally {
            // Critical non-cancellable section: only the terminal DB upsert (and the
            // image drain that feeds it). A stopped/superseded generation MUST still
            // write its final row so it isn't left as SENDING. Everything else — RAG
            // indexing, UI cleanup, foreground release, notifications — is moved below
            // so a Stop returns from here as soon as the row is written, instead of
            // running a heavy non-cancellable tail that held the generation lock/queue.
            withContext(NonCancellable) {
                // A cancellation can arrive as ImageGenToolProvider's withContext returns,
                // after the file was queued but before the normal post-tool drain ran.
                generatedImages.addAll(imageGenToolProvider.drainImages(conversationId))
                try {
                    if (isLatestPersist()) {
                        val conversationExists = conversations.getConversation(conversationId) != null
                        if (conversationExists) {
                            finishCurrentThoughtTiming()
                            val finalSegments = buildLiveSegments(
                                segments,
                                currentAnswerBuf,
                                currentThoughtBuf,
                                currentThoughtSignature,
                                currentThoughtDurationMs.takeIf { it > 0L }
                            )
                                ?: segments.toList().ifEmpty { null }
                            // Bound the row's toolCallJson aggregate (#51) and the unbounded answer
                            // text column — together they can exceed the 2MB CursorWindow otherwise.
                            val segmentsJson = MessagePersistenceGuard.encodeSegmentsBounded(finalSegments)
                            val effectiveParentId = parentId
                            val terminalEntity = MessageEntity(
                                id = modelMessageId, conversationId = conversationId, parentId = effectiveParentId,
                                text = MessagePersistenceGuard.clipText(totalText), images = generatedImages.toList(),
                                thoughts = totalThoughts.ifBlank { null },
                                thoughtTitle = totalThoughtTitle, tokenCount = totalTokenCount,
                                status = currentStatus, participant = Participant.MODEL, timestamp = startTime,
                                thoughtTimeMs = totalThoughtTimeMs, modelName = modelName, toolCallJson = segmentsJson
                            )
                            var verified = false
                            var lastFailure: Throwable? = null
                            repeat(3) { attempt ->
                                try {
                                    conversations.upsertMessage(terminalEntity)
                                    val stored = conversations.getMessagesByIds(listOf(modelMessageId)).firstOrNull()
                                    val expected = MessagePersistenceGuard.sanitize(terminalEntity)
                                    verified = stored != null &&
                                        stored.conversationId == conversationId &&
                                        stored.status == expected.status &&
                                        stored.text == expected.text &&
                                        stored.thoughts == expected.thoughts &&
                                        stored.toolCallJson == expected.toolCallJson
                                    if (verified) return@repeat
                                    lastFailure = IllegalStateException("terminal message verification mismatch (attempt ${attempt + 1})")
                                } catch (failure: Throwable) {
                                    if (failure is CancellationException) throw failure
                                    lastFailure = failure
                                }
                            }
                            if (!verified) {
                                throw IllegalStateException(
                                    "Terminal message was not durably verified for $conversationId/$modelMessageId",
                                    lastFailure,
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.e("AgoraVM", "Failed to persist message to DB", e)
                }
            }
            // Movable tail (cancellable, no suspension points): runs to completion even
            // on cancellation because none of these suspend. Kept OUT of NonCancellable
            // so a heavy RAG-indexing callback or notification can't pin the generation.
            // RAG indexing hook — fire-and-forget; the persist above already committed.
            try {
                if (isLatestPersist() && totalText.isNotBlank()) {
                    onMessagePersisted?.invoke(modelMessageId, totalText)
                }
            } catch (_: Exception) { /* indexing must never break terminal cleanup */ }
            // Terminal UI cleanup. Token-gated at the sink (in ChatViewModel), so they
            // no-op when this generation was stopped/superseded — only the still-current
            // generation resets the loading/streaming/generating-id UI state.
            onStreamClear()
            onLoadingChange(false)
            // The generating flag + active-set are released by the controller's endGeneration()
            // in its finally (which also drains the queue), so the slot lifecycle has a single owner.
            if (foregroundLeaseAcquired) {
                AgoraForegroundService.release(app, modelMessageId)
            }
            if (!AppForegroundTracker.isInForeground && currentStatus == MessageStatus.SUCCESS && totalText.isNotBlank()) {
                AgoraForegroundService.showCompletionNotification(app, totalText, conversationId)
            }
        }
    }
}
