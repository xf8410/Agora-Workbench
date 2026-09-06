package com.newoether.agora.api.openai

import com.newoether.agora.api.*
import com.newoether.agora.util.DebugLog
import com.newoether.agora.api.util.StreamingThinkTagParser
import com.newoether.agora.api.util.convertToOpenAiMessages
import com.newoether.agora.api.util.prepareMessages
import com.newoether.agora.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

abstract class BaseOpenAiProvider : LlmProvider {
    private companion object {
        /** Bounded wait for [DONE] / trailing usage once the terminal finish_reason arrived. */
        const val POST_TERMINAL_READ_SECONDS = 3L
        /** Line cap for the post-terminal tail so a chatty server cannot pin the stream open. */
        const val POST_TERMINAL_MAX_LINES = 20
    }

    protected val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    protected open fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig): OpenAiChatRequest = request
    protected open fun getExtraHeaders(config: ProviderConfig): Map<String, String> = emptyMap()
    protected open fun transformSystemPrompt(prompt: String?): String? = prompt

    /** Parse both structured reasoning_content and inline <think> blocks carried by content. */
    protected open suspend fun parseDeltaContent(
        delta: OpenAiDelta,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        delta.reasoningContent?.let { reasoning ->
            if (reasoning.isNotEmpty() && config.thinkingEnabled) {
                emit(StreamEvent.ThoughtChunk(reasoning))
            }
        }
        delta.content?.let { content ->
            if (content.isNotEmpty()) {
                thinkParser.feed(
                    content = content,
                    thinkingEnabled = config.thinkingEnabled,
                    onText = { emit(StreamEvent.TextChunk(it)) },
                    onThought = { emit(StreamEvent.ThoughtChunk(it)) }
                )
            }
        }
    }

    protected open val retryableStatusCodes: Set<Int> = setOf(429, 502, 503, 504)
    protected open val retryMissingV1BaseUrl: Boolean = false
    protected open fun retryDelayMillis(statusCode: Int, attempt: Int): Long = 1000L * attempt

    override fun generateResponse(messages: List<ChatMessage>, config: ProviderConfig): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val endpointUrls = endpointCandidates(baseUrl, "chat/completions")
        val validatedMessages = prepareMessages(messages, config.maxContextWindow)
        val apiMessages = convertToOpenAiMessages(
            messages = validatedMessages,
            systemPrompt = transformSystemPrompt(config.systemPrompt),
            includeImages = config.includeImages
        )
        var request = OpenAiChatRequest(
            model = config.modelId,
            messages = apiMessages,
            stream = true,
            streamOptions = OpenAiStreamOptions(includeUsage = true),
            tools = config.tools,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            topP = config.topP,
            frequencyPenalty = config.frequencyPenalty,
            presencePenalty = config.presencePenalty
        )
        request = customizeRequest(request, config)
        val thinkParser = StreamingThinkTagParser()

        try {
            val requestBodyJson = json.encodeToString(OpenAiChatRequest.serializer(), request)
            DebugLog.d("AgoraAPI", "[$name] REQ -> ${endpointUrls.first()} | model=${config.modelId} | msgs=${apiMessages.size} | tools=${config.tools?.size ?: 0}")
            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
            for ((key, value) in getExtraHeaders(config)) headers[key] = value

            val maxAttempts = 3
            var attempt = 0
            var finished = false
            while (attempt < maxAttempts && !finished) {
                attempt++
                var endpointIndex = 0
                var retryScheduled = false
                while (endpointIndex < endpointUrls.size && !finished && !retryScheduled) {
                    val endpointUrl = endpointUrls[endpointIndex]
                    val handle = HttpClient.streamPost(endpointUrl, requestBodyJson, headers)
                    try {
                        if (handle.code == 200) {
                            consumeSuccessfulStream(handle, config, thinkParser) { emit(it) }
                            finished = true
                        } else {
                            val errorRaw = handle.errorBody ?: "Unknown error"
                            val hasV1Fallback = endpointIndex + 1 < endpointUrls.size
                            if (hasV1Fallback) {
                                DebugLog.w("AgoraAPI", "[$name] ${handle.code} at $endpointUrl, retrying with ${endpointUrls[endpointIndex + 1]}")
                                endpointIndex++
                                continue
                            }
                            DebugLog.e("AgoraAPI", "[$name] ERR ${handle.code} at $endpointUrl: $errorRaw")
                            if (handle.code in retryableStatusCodes && attempt < maxAttempts) {
                                val retryDelayMs = retryDelayMillis(handle.code, attempt)
                                DebugLog.w("AgoraAPI", "[$name] Transient error ${handle.code} on attempt $attempt/$maxAttempts, retrying in ${retryDelayMs}ms...")
                                emit(StreamEvent.Retrying(attempt, maxAttempts))
                                delay(retryDelayMs)
                                retryScheduled = true
                            } else {
                                emit(StreamEvent.Error(buildGenerationError(handle.code, errorRaw, endpointUrls)))
                                finished = true
                            }
                        }
                    } finally {
                        handle.close()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            emit(StreamEvent.Error(GenerationError.Timeout))
        } catch (e: ConnectException) {
            emit(StreamEvent.Error(GenerationError.Network(0, e.localizedMessage ?: "Connection refused")))
        } catch (e: UnknownHostException) {
            emit(StreamEvent.Error(GenerationError.Network(0, e.localizedMessage ?: "Unknown host")))
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) emit(StreamEvent.Error(GenerationError.Unknown(e)))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun consumeSuccessfulStream(
        handle: HttpClient.StreamHandle,
        config: ProviderConfig,
        thinkParser: StreamingThinkTagParser,
        emit: suspend (StreamEvent) -> Unit
    ) {
        val pendingToolCalls = mutableMapOf<Int, PendingToolCall>()
        val contentBuf = StringBuilder()
        val emitAndAccumulate: suspend (StreamEvent) -> Unit = { event ->
            if (event is StreamEvent.TextChunk) contentBuf.append(event.text)
            emit(event)
        }
        var structuredToolCallsEmitted = false
        // Post-terminal tail state. The stream read timeout is unlimited (slow-thinking models
        // must never be cut off mid-stream), so once the terminal finish_reason is seen the
        // socket read timeout is tightened and remaining reads are line-capped — otherwise a
        // server that omits [DONE] and holds the keep-alive connection open would block this
        // loop forever after the answer already completed (UI stuck "generating" until Stop).
        var sawTerminalFinish = false
        var tailLineCount = 0

        /** Emits accumulated structured tool calls; true when anything was emitted. */
        suspend fun flushPendingToolCalls(): Boolean {
            val calls = pendingToolCalls.values.filter { it.name.isNotEmpty() }.map {
                StreamEvent.ToolCallRequest(
                    id = it.id.ifBlank { syntheticToolCallId() },
                    name = it.name,
                    arguments = it.args.toString(),
                )
            }
            pendingToolCalls.clear()
            if (calls.isEmpty()) return false
            if (calls.size == 1) emit(calls.first())
            else emit(StreamEvent.ToolCallsRequest(calls))
            return true
        }

        while (currentCoroutineContext().isActive) {
            val line = try {
                handle.readLine()
            } catch (e: SocketTimeoutException) {
                // Mid-stream gaps (slow first token, long thinking) keep waiting; only the
                // post-terminal tail treats a read timeout as end-of-stream.
                if (!currentCoroutineContext().isActive) break
                if (sawTerminalFinish) break
                continue
            } ?: break
            if (sawTerminalFinish) {
                tailLineCount++
                if (tailLineCount > POST_TERMINAL_MAX_LINES) break
            }
            // SSE spec allows "data:" with or without the trailing space.
            if (!line.startsWith("data:")) continue
            val jsonStr = line.substring(5).trim()
            if (jsonStr == "[DONE]") break
            try {
                val response = json.decodeFromString<OpenAiStreamResponse>(jsonStr)
                val choice = response.choices?.firstOrNull()
                choice?.delta?.let { delta ->
                    parseDeltaContent(delta, config, thinkParser, emitAndAccumulate)
                    delta.toolCalls?.forEach { tc ->
                        val existing = if (!tc.id.isNullOrBlank()) pendingToolCalls.values.firstOrNull { it.id == tc.id } else null
                        val pending = existing ?: run {
                            val idx = when {
                                tc.index != null -> tc.index
                                pendingToolCalls.isEmpty() -> 0
                                // Deltas carrying neither index nor id belong to the most recent
                                // (still-active) tool call — allocating a fresh slot here would
                                // strand every argument chunk on a nameless call, which the
                                // emit filter then drops (lost tool arguments).
                                else -> pendingToolCalls.keys.max()
                            }
                            pendingToolCalls.getOrPut(idx) { PendingToolCall() }
                        }
                        if (!tc.id.isNullOrBlank()) pending.id = tc.id
                        tc.function?.name?.let { if (it.isNotEmpty()) pending.name = it }
                        tc.function?.arguments?.let { pending.args.append(if (it is JsonPrimitive) it.content else it.toString()) }
                    }
                }
                if (choice?.finishReason == "tool_calls" && pendingToolCalls.isNotEmpty()) {
                    if (flushPendingToolCalls()) structuredToolCallsEmitted = true
                }
                response.usage?.let { emit(StreamEvent.UsageUpdate(it.toTokenUsage())) }
                if (isTerminalOpenAiSseLine(line)) {
                    // Terminal line was still delivered and parsed above (usage / final tool
                    // metadata). Switch to the bounded tail: [DONE] or trailing usage may
                    // still arrive; silence now ends the stream instead of blocking forever.
                    sawTerminalFinish = true
                    tailLineCount = 0
                    runCatching {
                        handle.source?.timeout()?.timeout(POST_TERMINAL_READ_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e)
            }
        }

        thinkParser.flush(
            onText = { emitAndAccumulate(StreamEvent.TextChunk(it)) },
            onThought = { emitAndAccumulate(StreamEvent.ThoughtChunk(it)) }
        )

        // Servers that report finish_reason "stop" (llama.cpp, several proxies) may still have
        // streamed structured tool_calls deltas; recover them here before falling back to the
        // content-text parser, which only sees text that made it into `content`.
        if (!structuredToolCallsEmitted && !config.tools.isNullOrEmpty() && flushPendingToolCalls()) {
            structuredToolCallsEmitted = true
        }
        if (!structuredToolCallsEmitted && !config.tools.isNullOrEmpty()) {
            val parsed = ToolCallTextParser.parse(contentBuf.toString())
            if (parsed.size == 1) {
                emit(StreamEvent.ToolCallRequest(syntheticToolCallId(), parsed[0].name, parsed[0].arguments))
            } else if (parsed.size > 1) {
                emit(StreamEvent.ToolCallsRequest(parsed.map {
                    StreamEvent.ToolCallRequest(syntheticToolCallId(), it.name, it.arguments)
                }))
            }
        }
        if (!currentCoroutineContext().isActive) throw CancellationException("Stream cancelled")
    }

    private fun endpointCandidates(baseUrl: String, path: String): List<String> {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val primary = "$normalizedBaseUrl/$cleanPath"
        if (!retryMissingV1BaseUrl || normalizedBaseUrl.isBlank() || BaseUrlResolver.hasVersionSegment(normalizedBaseUrl)) {
            return listOf(primary)
        }
        return listOf(primary, "$normalizedBaseUrl/v1/$cleanPath")
    }

    private fun syntheticToolCallId(): String = "call_text_${java.util.UUID.randomUUID()}"

    private fun buildGenerationError(statusCode: Int, errorRaw: String, endpointUrls: List<String>): GenerationError {
        val endpointHint = if (statusCode == 404 && endpointUrls.size > 1) {
            "\nTried ${endpointUrls.joinToString(" and ")}. OpenAI-compatible servers often require a /v1 Base URL."
        } else ""
        return try {
            val errorJson = json.decodeFromString<OpenAiErrorResponse>(errorRaw)
            GenerationError.Api(errorJson.error.code ?: statusCode.toString(), errorJson.error.type, errorJson.error.message + endpointHint)
        } catch (_: Exception) {
            GenerationError.Network(statusCode, errorRaw + endpointHint)
        }
    }

    private fun authHeaders(apiKey: String): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        try {
            val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
            val endpointUrls = endpointCandidates(effectiveBaseUrl, "models")
            val headers = authHeaders(apiKey)
            var lastParseError: Exception? = null
            for ((index, endpointUrl) in endpointUrls.withIndex()) {
                val responseText = HttpClient.fetchModels(endpointUrl, headers)
                if (responseText == null) {
                    if (index < endpointUrls.lastIndex) DebugLog.w("AgoraAPI", "Failed to fetch $name models from $endpointUrl; retrying ${endpointUrls[index + 1]}")
                    continue
                }
                try {
                    return@withContext json.decodeFromString<OpenAiModelListResponse>(responseText).data.map { it.id }.sorted()
                } catch (e: Exception) {
                    lastParseError = e
                    if (index < endpointUrls.lastIndex) DebugLog.w("AgoraAPI", "Failed to parse $name models from $endpointUrl; retrying ${endpointUrls[index + 1]}")
                }
            }
            if (lastParseError != null) DebugLog.e("AgoraAPI", "Failed to parse $name models", lastParseError)
            else DebugLog.e("AgoraAPI", "Failed to fetch $name models: empty response")
            emptyList()
        } catch (e: Exception) {
            DebugLog.e("AgoraAPI", "Failed to fetch $name models", e)
            emptyList()
        }
    }
}
