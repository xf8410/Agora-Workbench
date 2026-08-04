package com.newoether.agora.viewmodel

import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.Collections

/** Fail-safe confirmation gate for GitHub mutations requested by the assistant. */
class GitHubConfirmationController {
    data class PendingGitHubAction(
        val repository: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    private val _pendingAction = MutableStateFlow<PendingGitHubAction?>(null)
    val pendingAction: StateFlow<PendingGitHubAction?> = _pendingAction.asStateFlow()

    /** Repositories approved for all mutations until this app process ends. */
    private val sessionAllowedRepositories = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun confirm(repository: String, summary: String): Boolean {
        if (sessionAllowedRepositories.contains(repository)) return true
        if (_pendingAction.value != null) return false
        val deferred = CompletableDeferred<Boolean>()
        val pending = PendingGitHubAction(repository, summary, deferred)
        _pendingAction.value = pending
        return try {
            withTimeout(Constants.SHELL_CONFIRM_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            if (_pendingAction.value?.deferred === deferred) _pendingAction.value = null
        }
    }

    /** Called by the UI. "Always allow" is process/session scoped, not permanently persisted. */
    fun resolve(allow: Boolean, alwaysAllowRepository: Boolean = false) {
        val pending = _pendingAction.value ?: return
        if (allow && alwaysAllowRepository) sessionAllowedRepositories.add(pending.repository)
        pending.deferred.complete(allow)
        _pendingAction.value = null
    }
}
