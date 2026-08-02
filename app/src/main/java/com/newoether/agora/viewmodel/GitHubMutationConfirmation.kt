package com.newoether.agora.viewmodel

/**
 * Process-local, fail-closed bridge from model-facing GitHub mutation providers to the foreground
 * confirmation controller. No Activity is retained: ChatViewModel installs a suspend callback and
 * clears it with its lifecycle. Headless/background execution therefore cannot merge a PR.
 */
object GitHubMutationConfirmation {
    @Volatile
    private var handler: (suspend (summary: String) -> Boolean)? = null

    fun install(callback: suspend (summary: String) -> Boolean) {
        handler = callback
    }

    fun clear() {
        handler = null
    }

    suspend fun confirm(summary: String): Boolean = handler?.invoke(summary) ?: false
}
