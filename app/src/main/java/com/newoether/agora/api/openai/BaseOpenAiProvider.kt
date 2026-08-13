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
    protected val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    protected open fun customizeRequest(request: OpenAiChatRequest, config: ProviderConfig) = request
    protected open fun getExtraHeaders(config: ProviderConfig): Map<String, String> = emptyMap()
    protected open fun transformSystemPrompt(prompt: String?): String? = prompt
    protected open suspend fun parseDeltaContent(delta: OpenAiDelta, config: ProviderConfig, thinkParser: StreamingThinkTagParser, emit: suspend (StreamEvent) -> Unit) {
        delta.reasoningContent?.takeIf { it.isNotEmpty() && config.thinkingEnabled }?.let { emit(StreamEvent.ThoughtChunk(it)) }
        delta.content?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.TextChunk(it)) }
    }
    protected open val retryableStatusCodes = setOf(429, 502, 503, 504)
    protected open val retryMissingV1BaseUrl = false
    protected open fun retryDelayMillis(statusCode: Int, attempt: Int) = 1000L * attempt

    override fun generateResponse(messages: List<ChatMessage>, config: ProviderConfig): Flow<StreamEvent> = flow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val endpointUrls = endpointCandidates(baseUrl, "chat/completions")
        val apiMessages = convertToOpenAiMessages(prepareMessages(messages, config.maxContextWindow), transformSystemPrompt(config.systemPrompt), config.includeImages)
        var request = OpenAiChatRequest(config.modelId, apiMessages, streamOptions = OpenAiStreamOptions(), tools = config.tools,
            temperature = config.temperature, maxTokens = config.maxTokens, topP = config.topP,
            frequencyPenalty = config.frequencyPenalty, presencePenalty = config.presencePenalty)
        request = customizeRequest(request, config)
        val parser = StreamingThinkTagParser()
        try {
            val body = json.encodeToString(OpenAiChatRequest.serializer(), request)
            val headers = mutableMapOf("Content-Type" to "application/json")
            if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
            headers.putAll(getExtraHeaders(config))
            var attempt = 0
            var finished = false
            while (attempt < 3 && !finished) {
                attempt++
                var endpointIndex = 0
                var retry = false
                while (endpointIndex < endpointUrls.size && !finished && !retry) {
                    val handle = HttpClient.streamPost(endpointUrls[endpointIndex], body, headers)
                    try {
                        if (handle.code == 200) {
                            consumeSuccessfulStream(handle, config, parser) { emit(it) }
                            finished = true
                        } else {
                            val raw = handle.errorBody ?: "Unknown error"
                            if (endpointIndex + 1 < endpointUrls.size) { endpointIndex++; continue }
                            if (handle.code in retryableStatusCodes && attempt < 3) {
                                emit(StreamEvent.Retrying(attempt, 3)); delay(retryDelayMillis(handle.code, attempt)); retry = true
                            } else { emit(StreamEvent.Error(buildGenerationError(handle.code, raw, endpointUrls))); finished = true }
                        }
                    } finally { handle.close() }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: SocketTimeoutException) { emit(StreamEvent.Error(GenerationError.Timeout)) }
        catch (e: ConnectException) { emit(StreamEvent.Error(GenerationError.Network(0, e.localizedMessage ?: "Connection refused"))) }
        catch (e: UnknownHostException) { emit(StreamEvent.Error(GenerationError.Network(0, e.localizedMessage ?: "Unknown host"))) }
        catch (e: Exception) { if (currentCoroutineContext().isActive) emit(StreamEvent.Error(GenerationError.Unknown(e))) }
    }.flowOn(Dispatchers.IO)

    private suspend fun consumeSuccessfulStream(handle: HttpClient.StreamHandle, config: ProviderConfig, thinkParser: StreamingThinkTagParser, emit: suspend (StreamEvent) -> Unit) {
        val pending = mutableMapOf<Int, PendingToolCall>()
        val content = StringBuilder()
        var structured = false
        while (currentCoroutineContext().isActive) {
            // A read timeout is terminal. The previous continue loop could display "Request timed
            // out" indefinitely while never closing the request or finalizing the message.
            val line = handle.readLine() ?: break
            if (!line.startsWith("data: ")) continue
            val raw = line.substring(6).trim()
            if (raw == "[DONE]") break
            try {
                val response = json.decodeFromString<OpenAiStreamResponse>(raw)
                val choice = response.choices?.firstOrNull()
                choice?.delta?.let { delta ->
                    parseDeltaContent(delta, config, thinkParser) { event ->
                        if (event is StreamEvent.TextChunk) content.append(event.text)
                        emit(event)
                    }
                    delta.toolCalls?.forEach { tc ->
                        val p = tc.id?.let { id -> pending.values.firstOrNull { it.id == id } }
                            ?: pending.getOrPut(tc.index ?: pending.size) { PendingToolCall() }
                        tc.id?.let { p.id = it }; tc.function?.name?.takeIf { it.isNotEmpty() }?.let { p.name = it }
                        tc.function?.arguments?.let { p.args.append(if (it is JsonPrimitive) it.content else it.toString()) }
                    }
                }
                if (choice?.finishReason == "tool_calls" && pending.isNotEmpty()) {
                    val calls = pending.values.filter { it.name.isNotEmpty() }.map { StreamEvent.ToolCallRequest(it.id, it.name, it.args.toString()) }
                    pending.clear(); structured = calls.isNotEmpty()
                    if (calls.size == 1) emit(calls.first()) else if (calls.isNotEmpty()) emit(StreamEvent.ToolCallsRequest(calls))
                }
                response.usage?.let { OpenAiUsageParser.parse(it) }?.let { emit(StreamEvent.UsageUpdate(it)) }
            } catch (e: Exception) { DebugLog.e("AgoraAPI", "Parse error: ${e.message}", e) }
        }
        thinkParser.flush(onText = { emit(StreamEvent.TextChunk(it)) }, onThought = { emit(StreamEvent.ThoughtChunk(it)) })
        if (!structured && !config.tools.isNullOrEmpty()) {
            val parsed = ToolCallTextParser.parse(content.toString())
            val calls = parsed.map { StreamEvent.ToolCallRequest("call_text_${java.util.UUID.randomUUID()}", it.name, it.arguments) }
            if (calls.size == 1) emit(calls.first()) else if (calls.isNotEmpty()) emit(StreamEvent.ToolCallsRequest(calls))
        }
        if (!currentCoroutineContext().isActive) throw CancellationException("Stream cancelled")
    }

    private fun endpointCandidates(baseUrl: String, path: String): List<String> {
        val b = baseUrl.trimEnd('/'); val primary = "$b/${path.trimStart('/')}"
        return if (!retryMissingV1BaseUrl || b.isBlank() || BaseUrlResolver.hasVersionSegment(b)) listOf(primary) else listOf(primary, "$b/v1/${path.trimStart('/')}")
    }
    private fun buildGenerationError(code: Int, raw: String, urls: List<String>): GenerationError {
        val hint = if (code == 404 && urls.size > 1) "\nTried ${urls.joinToString(" and ")}." else ""
        return try { val e = json.decodeFromString<OpenAiErrorResponse>(raw).error; GenerationError.Api(e.code ?: code.toString(), e.type, e.message + hint) }
        catch (_: Exception) { GenerationError.Network(code, raw + hint) }
    }
    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> = withContext(Dispatchers.IO) {
        val effective = baseUrl?.trimEnd('/')?.ifBlank { null } ?: defaultBaseUrl
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        for (url in endpointCandidates(effective, "models")) {
            val text = HttpClient.fetchModels(url, headers) ?: continue
            runCatching { json.decodeFromString<OpenAiModelListResponse>(text).data.map { it.id }.sorted() }.getOrNull()?.let { return@withContext it }
        }
        emptyList()
    }
}
