package com.newoether.agora.viewmodel

import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/** Fail-safe confirmation gate for GitHub mutations requested by the assistant. */
class GitHubConfirmationController {
    data class PendingGitHubAction(
        val repository: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    private val _pendingAction = MutableStateFlow<PendingGitHubAction?>(null)
    val pendingAction: StateFlow<PendingGitHubAction?> = _pendingAction.asStateFlow()

    suspend fun confirm(repository: String, summary: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        _pendingAction.value = PendingGitHubAction(repository, summary, deferred)
        return try {
            withTimeout(Constants.SHELL_CONFIRM_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            if (_pendingAction.value?.deferred === deferred) _pendingAction.value = null
        }
    }

    fun resolve(allow: Boolean) {
        val pending = _pendingAction.value ?: return
        pending.deferred.complete(allow)
        _pendingAction.value = null
    }
}
