package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/** Fail-safe confirmation gate for GitHub mutations requested by the assistant.
 *  "Always allow" is persisted across app restarts and shared across all conversations/windows. */
class GitHubConfirmationController(context: Context) {
    data class PendingGitHubAction(
        val repository: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    private val _pendingAction = MutableStateFlow<PendingGitHubAction?>(null)
    val pendingAction: StateFlow<PendingGitHubAction?> = _pendingAction.asStateFlow()

    private val prefs = context.getSharedPreferences("github_confirmation", Context.MODE_PRIVATE)

    /** Persistently approved repositories — survives app restarts, shared across all windows. */
    private fun allowedSet(): Set<String> = prefs.getStringSet("allowed_repositories", emptySet()) ?: emptySet()

    private fun addAllowed(repository: String) {
        val current = allowedSet().toMutableSet()
        current.add(repository)
        prefs.edit().putStringSet("allowed_repositories", current).apply()
    }

    /** Revoke a repository from the persistent allow-list (for settings UI). */
    fun revokeAllowed(repository: String) {
        val current = allowedSet().toMutableSet()
        current.remove(repository)
        prefs.edit().putStringSet("allowed_repositories", current).apply()
    }

    /** Snapshot of all persistently approved repositories (for settings UI). */
    fun allowedRepositories(): Set<String> = allowedSet()

    suspend fun confirm(repository: String, summary: String): Boolean {
        if (allowedSet().contains(repository)) return true
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

    /** Called by the UI. "Always allow" is persisted permanently across restarts and windows. */
    fun resolve(allow: Boolean, alwaysAllowRepository: Boolean = false) {
        val pending = _pendingAction.value ?: return
        if (allow && alwaysAllowRepository) addAllowed(pending.repository)
        pending.deferred.complete(allow)
        _pendingAction.value = null
    }
}
