package com.newoether.agora.viewmodel

import com.newoether.agora.api.LocalModelSerializer
import com.newoether.agora.api.ProviderConfig
import com.newoether.agora.api.StreamEvent
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.Agent
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.ModelId
import com.newoether.agora.model.Participant
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outcome of one sequential agent relay. [text] holds everything produced so far. */
data class RelayOutcome(
    val success: Boolean,
    val text: String,
    val error: String? = null,
)

/**
 * Sequential multi-agent relay ("顺序分工接力"): every enabled agent in the conversation's
 * team takes one turn, in order. Each agent sees the user request plus the output of the
 * previous teammates, generates with its own [Agent.providerKey] model and [Agent.rolePrompt]
 * system prompt, and appends its labelled section to the shared reply.
 *
 * Deliberately uses the bare-provider pattern (mirrors title generation in
 * [MessageGenerationController]) instead of [GenerationManager]: the relay drives ONE model
 * message end-to-end and must not fight the per-message finalizer. A teammate failure SKIPS
 * that teammate and continues the relay; accumulated text is preserved so nothing already
 * generated is lost.
 *
 * ## Why text-first-then-thoughts (the "sometimes no reply" fix)
 *
 * Reasoning models (glm / mimo / deepseek-r1 style endpoints) frequently put ALL of their
 * output in `reasoning_content` and stream little or no `content`. The old collector read only
 * TextChunk and disabled thinking (`thinkingEnabled = false`), so the provider dropped the
 * reasoning events entirely — the agent "succeeded" with an empty contribution and the relay
 * silently produced nothing. Now:
 *  1. `thinkingEnabled = true` — purely client-side event routing; it adds NO field to the
 *     outgoing request (OpenAiChatRequest has no thinking parameter), so gateway behavior is
 *     untouched, but `reasoning_content` now arrives as StreamEvent.ThoughtChunk.
 *  2. Text is preferred; if an agent produced thoughts but no text, the thought text becomes
 *     its contribution instead of an empty section.
 */
class AgentRelayRunner(
    private val settings: SettingsRepository,
    private val requestBuilder: GenerationRequestBuilder,
    private val providerRegistry: ProviderRegistry,
) {

    suspend fun run(
        team: List<Agent>,
        userText: String,
        historyText: String,
        fallbackModelId: String,
        onSection: suspend (accumulated: String) -> Unit,
    ): RelayOutcome {
        val sections = StringBuilder()
        val failures = mutableListOf<String>()
        team.forEach { agent ->
            val modelIdWithPrefix = agent.providerKey.ifBlank { fallbackModelId }
            val resolved = requestBuilder.resolveProviderKey(modelIdWithPrefix)
            if (resolved == null) {
                // Skip-and-continue: one broken teammate must not erase the work of the others.
                failures.add("「${agent.name}」未找到模型提供方")
                DebugLog.w("AgentRelay", "Skip ${agent.name}: no provider for $modelIdWithPrefix")
                return@forEach
            }
            val (providerName, activeKey) = resolved
            // Re-resolve against on-disk settings (same DataStore race guard as launchGeneration).
            val freshKey = settings.awaitActiveKey(providerName)?.takeIf { it.isNotBlank() } ?: activeKey
            val provider = providerRegistry.getInstance(providerName)
            if (provider == null) {
                failures.add("「${agent.name}」未找到提供方实例")
                DebugLog.w("AgentRelay", "Skip ${agent.name}: provider instance $providerName missing")
                return@forEach
            }

            val prior = sections.toString().trim()
            val prompt = buildString {
                if (historyText.isNotBlank()) {
                    append("对话历史：\n").append(historyText).append("\n\n")
                }
                append("用户请求：\n").append(userText).append("\n\n")
                if (prior.isNotEmpty()) {
                    append("前面队友已完成的输出：\n").append(prior).append("\n\n")
                }
                append("你是多智能体团队中的「${agent.name}」")
                if (agent.rolePrompt.isNotBlank()) append("，职责：${agent.rolePrompt.take(200)}")
                append("。请按顺序接力：基于用户请求")
                if (prior.isNotEmpty()) append("和前面队友的输出")
                append("，完成你负责的部分。直接输出你的贡献内容，不要重复队友已有的内容，不要添加额外说明。")
            }
            val config = ProviderConfig(
                apiKey = freshKey,
                modelId = ModelId.parse(modelIdWithPrefix).modelName,
                systemPrompt = agent.rolePrompt,
                maxContextWindow = 1,
                // Client-side event routing only — adds nothing to the wire request. Required so
                // reasoning models surface their reasoning_content instead of streaming nothing.
                thinkingEnabled = true,
                baseUrl = providerRegistry.getEffectiveBaseUrl(providerName)
            )

            val output = StringBuilder()
            val thoughts = StringBuilder()
            var streamError: String? = null
            try {
                val messages = listOf(
                    ChatMessage(text = prompt, participant = Participant.USER, status = MessageStatus.SUCCESS)
                )
                if (providerName == Constants.PROVIDER_LOCAL) {
                    // Local agents share the chat engine: serialize like title generation (OOM guard).
                    LocalModelSerializer.mutex.withLock {
                        withContext(Dispatchers.IO) {
                            provider.generateResponse(messages, config).collect { event ->
                                when (event) {
                                    is StreamEvent.TextChunk -> output.append(event.text)
                                    is StreamEvent.ThoughtChunk -> thoughts.append(event.thought)
                                    is StreamEvent.Error -> streamError = event.message
                                    else -> Unit
                                }
                            }
                        }
                    }
                } else {
                    provider.generateResponse(messages, config).collect { event ->
                        when (event) {
                            is StreamEvent.TextChunk -> output.append(event.text)
                            is StreamEvent.ThoughtChunk -> thoughts.append(event.thought)
                            is StreamEvent.Error -> streamError = event.message
                            else -> Unit
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e("AgentRelay", "Agent ${agent.name} failed", e)
                failures.add("「${agent.name}」执行失败：${e.message ?: "未知错误"}")
                onSection(sections.toString().trim())
                return@forEach
            }

            // Text-first, thoughts-fallback: a reasoning model that answered entirely inside
            // reasoning_content still contributes its thinking text instead of nothing.
            val contribution = output.toString().trim().ifEmpty { thoughts.toString().trim() }
            if (contribution.isNotEmpty()) {
                sections.append("【").append(agent.name).append("】\n").append(contribution).append("\n\n")
                onSection(sections.toString().trim())
            } else {
                val reason = streamError?.let { "流错误：$it" } ?: "模型返回为空"
                failures.add("「${agent.name}」$reason")
                DebugLog.w("AgentRelay", "Skip ${agent.name}: empty contribution (${streamError ?: "no text"})")
            }
        }

        // Preserve partial results: if at least one teammate contributed, the relay succeeded
        // and the failures are annotated in the text instead of discarding everything.
        return if (sections.isNotBlank() || failures.isEmpty()) {
            val annotated = if (failures.isNotEmpty()) {
                sections.toString().trim() + "\n\n" + failures.joinToString("\n") { "⚠️ $it" }
            } else {
                sections.toString().trim()
            }
            RelayOutcome(true, annotated)
        } else {
            RelayOutcome(false, sections.toString().trim(), failures.joinToString("；"))
        }
    }

    companion object {
        /** Compact recent selected-path history for relay context (bounded per message). */
        fun historyText(path: List<ChatMessage>): String =
            path.filter { it.text.isNotBlank() }
                .takeLast(10)
                .joinToString("\n") { msg ->
                    val who = if (msg.participant == Participant.USER) "用户" else "助手"
                    "$who: ${msg.text.take(800)}"
                }
    }
}
