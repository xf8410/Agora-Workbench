package com.newoether.agora.viewmodel

import com.newoether.agora.api.LlmProvider
import com.newoether.agora.api.anthropic.AnthropicProvider
import com.newoether.agora.api.gemini.GeminiProvider
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.api.ollama.OllamaProvider
import com.newoether.agora.api.openai.CustomOpenAiProvider
import com.newoether.agora.api.openai.DeepSeekProvider
import com.newoether.agora.api.openai.GroqProvider
import com.newoether.agora.api.openai.OpenAiProvider
import com.newoether.agora.api.openai.OpenRouterProvider
import com.newoether.agora.api.openai.QwenProvider
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.ModelId
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/** Pure policy boundary used by both production code and JVM tests. Missing providers fail shut. */
internal fun providerConfigurationIsValid(
    providerName: String,
    activeKey: String,
    registered: Boolean,
    builtIn: Boolean,
    effectiveBaseUrl: String?,
): Boolean = when {
    providerName == Constants.PROVIDER_UNKNOWN -> false
    !registered -> false
    providerName == Constants.PROVIDER_LOCAL -> true
    !builtIn || providerName == Constants.PROVIDER_OLLAMA -> !effectiveBaseUrl.isNullOrBlank()
    else -> activeKey.isNotBlank()
}

/**
 * Owns the set of LLM providers — built-in plus user-defined custom OpenAI-compatible
 * ones — and all logic for resolving a model/provider to a concrete [LlmProvider]
 * instance, its effective base URL, and its configured/credentialed status.
 *
 * Extracted from [ChatViewModel] so provider lifecycle (registration, rename, delete,
 * credential reconciliation) and model discovery live in one cohesive place. The live
 * [all] map is shared by reference with the generation pipeline, so it is a
 * [ConcurrentHashMap]: mutated by the sync collectors while read on `Dispatchers.IO`
 * during generation.
 */
class ProviderRegistry(
    private val settings: SettingsRepository,
    localProvider: LocalProvider,
    private val scope: CoroutineScope,
) {
    private val builtInProviders: Map<String, LlmProvider> = mapOf(
        Constants.PROVIDER_GOOGLE to GeminiProvider(),
        Constants.PROVIDER_OPENAI to OpenAiProvider(),
        Constants.PROVIDER_ANTHROPIC to AnthropicProvider(),
        Constants.PROVIDER_DEEPSEEK to DeepSeekProvider(),
        Constants.PROVIDER_QWEN to QwenProvider(),
        Constants.PROVIDER_GROQ to GroqProvider(),
        Constants.PROVIDER_OLLAMA to OllamaProvider(),
        Constants.PROVIDER_OPEN_ROUTER to OpenRouterProvider(),
        Constants.PROVIDER_LOCAL to localProvider
    )

    // Declared as MutableMap so `in`/`contains` keep Map (containsKey) semantics (KT-18053).
    private val providers: MutableMap<String, LlmProvider> = ConcurrentHashMap(builtInProviders)
    private val initialCustomProviderSync = CompletableDeferred<Unit>()

    /** Live, thread-safe read view shared with the generation pipeline. */
    val all: Map<String, LlmProvider> get() = providers

    fun isBuiltIn(name: String): Boolean = name in builtInProviders

    fun getInstance(name: String): LlmProvider = requireNotNull(providers[name]) {
        "Provider is not registered: $name"
    }

    fun getEffectiveBaseUrl(providerName: String): String? =
        settings.providerBaseUrls.value[providerName]?.takeIf { it.isNotBlank() }
            ?: providers[providerName]?.takeIf { !isBuiltIn(providerName) }?.defaultBaseUrl

    fun isConfigured(providerName: String, activeKey: String): Boolean =
        providerConfigurationIsValid(
            providerName = providerName,
            activeKey = activeKey,
            registered = providerName in providers,
            builtIn = isBuiltIn(providerName),
            effectiveBaseUrl = getEffectiveBaseUrl(providerName),
        )

    fun providerForModel(modelId: String): String {
        // Prefixed IDs (e.g. "OpenAI:gpt-4"): extract provider directly
        if (modelId.contains(":")) return ModelId.parse(modelId).providerName
        // Unprefixed IDs: user-registered providers take priority over heuristics
        settings.availableModels.value.forEach { (providerName, models) ->
            if (models.contains(modelId)) return providerName
        }
        // Heuristic fallback for legacy unprefixed IDs
        return ModelId.parse(modelId).providerName
    }

    // ── Custom provider CRUD ──────────────────────────────────
    // Settings persists the config; the callbacks keep the live `providers` map in sync.

    fun addCustom(name: String, baseUrl: String) {
        providers[name] = CustomOpenAiProvider(name, baseUrl)
        settings.addCustomProvider(name, baseUrl) { n, p -> providers[n] = p }
    }

    fun renameCustom(oldName: String, newName: String) {
        val url = settings.providerBaseUrls.value[oldName] ?: return
        providers.remove(oldName)
        providers[newName] = CustomOpenAiProvider(newName, url)
        settings.renameCustomProvider(oldName, newName, { providers.remove(it) }, { n, p -> providers[n] = p })
    }

    fun deleteCustom(name: String) {
        settings.deleteCustomProvider(name) { providers.remove(it) }
    }

    /** Registers any persisted custom provider not yet present in the live map. */
    fun ensureCustomProvidersRegistered() {
        settings.customProviders.value.forEach { config ->
            if (config.name !in providers) {
                providers[config.name] = CustomOpenAiProvider(config.name, settings.providerBaseUrls.value[config.name] ?: "")
            }
        }
    }

    /** Waits until the live map reflects persisted custom provider names and base URLs. */
    suspend fun awaitInitialSync() = initialCustomProviderSync.await()

    /**
     * Fetches the live model list for a single provider and caches it. Unlike a full
     * sync this carries no global side effects (no snackbar, no syncing flag).
     */
    suspend fun fetchModelsForProvider(name: String): List<String> {
        if (name == Constants.PROVIDER_LOCAL) return emptyList()
        ensureCustomProvidersRegistered()
        val provider = providers[name] ?: return emptyList()
        val activeKey = settings.apiKeys.value.find { it.id == settings.activeApiKeyIds.value[name] }?.key ?: ""
        if (!isConfigured(name, activeKey)) return emptyList()
        val baseUrl = if (!isBuiltIn(name)) {
            settings.providerBaseUrls.value[name]?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        } else {
            // Built-in: a blank persisted entry is the same as absent (use the provider default).
            // Without this guard, "" flows straight to the provider, which only elvis-checks null
            // and would build a malformed relative endpoint.
            settings.providerBaseUrls.value[name]?.takeIf { it.isNotBlank() }
        }

        // Resolve the "/v1" ambiguity ONCE here (config time) and persist the canonical
        // Base URL, so the request hot path uses a single deterministic endpoint instead
        // of trying both forms (and eating a 404) on every call. Custom OpenAI-compatible
        // providers without a version segment are probed /v1-first (the common case).
        val candidates: List<String?> =
            if (!isBuiltIn(name) && baseUrl != null && !com.newoether.agora.api.BaseUrlResolver.hasVersionSegment(baseUrl))
                listOf(com.newoether.agora.api.BaseUrlResolver.withV1(baseUrl), baseUrl)
            else
                listOf(baseUrl)

        for (candidate in candidates) {
            val raw = withTimeout(Constants.MODEL_FETCH_TIMEOUT_MS) { provider.fetchModels(activeKey, candidate) }
            if (raw.isEmpty()) continue
            // Persist the working form when it differs from what was stored, so the
            // ambiguity is never re-litigated at request time.
            if (candidate != null && candidate != baseUrl) settings.setProviderBaseUrl(name, candidate)
            val prefixed = raw.map { "$name:${it.removePrefix("models/")}" }
            settings.saveAvailableModels(name, prefixed)
            return prefixed
        }
        return emptyList()
    }

    /** Identity fingerprint of all providers' credentials/URLs — used to skip redundant syncs. */
    fun computeFingerprint(): String = providers.map { (name, _) ->
        val keyId = settings.activeApiKeyIds.value[name] ?: ""
        val url = settings.providerBaseUrls.value[name] ?: ""
        "$name|$keyId|$url"
    }.sorted().joinToString(",").hashCode().toString()

    /** Starts the long-lived collectors that keep the provider map and caches consistent. */
    fun launchSyncJobs() {
        // Sync custom providers into the live map whenever the persisted set changes.
        scope.launch {
            try {
                // Avoid treating the eager empty default as an authoritative provider set during
                // a Worker cold start. The first collected value is now the on-disk snapshot.
                settings.awaitInitialLoad()
                settings.customProviders.collect { custom ->
                    providers.keys.filter { !isBuiltIn(it) }.forEach { providers.remove(it) }
                    val baseUrls = settings.getProviderBaseUrls()
                    custom.forEach { config ->
                        providers[config.name] = CustomOpenAiProvider(
                            config.name,
                            baseUrls[config.name] ?: "",
                        )
                    }
                    initialCustomProviderSync.complete(Unit)
                }
            } catch (error: Throwable) {
                initialCustomProviderSync.completeExceptionally(error)
                throw error
            }
        }
        // Auto-clear cached available models when a provider loses its credentials.
        scope.launch {
            var prevConfigured = emptyMap<String, Boolean>()
            combine(
                settings.apiKeys,
                settings.activeApiKeyIds,
                settings.providerBaseUrls
            ) { keys, activeIds, baseUrls -> Triple(keys, activeIds, baseUrls) }
                .collect { (keys, activeIds, _) ->
                    if (keys.isEmpty() && activeIds.isEmpty()) return@collect

                    val current = mutableMapOf<String, Boolean>()
                    providers.toMap().forEach { (name, _) ->
                        val activeKey = keys.find { it.id == activeIds[name] }?.key ?: ""
                        current[name] = isConfigured(name, activeKey)
                    }

                    var changed = false
                    current.forEach { (name, configured) ->
                        if (prevConfigured[name] == true && !configured) {
                            val existing = settings.getAvailableModels()[name]
                            if (!existing.isNullOrEmpty()) {
                                settings.saveAvailableModels(name, emptyList())
                                changed = true
                            }
                        }
                    }
                    prevConfigured = current

                    if (changed) {
                        val allAvailable = settings.getAvailableModels().values.flatten().toSet()
                        val newEnabled = settings.enabledModels.value.intersect(allAvailable)
                        if (newEnabled != settings.enabledModels.value) {
                            settings.setEnabledModels(newEnabled)
                        }
                    }
                }
        }
    }
}
