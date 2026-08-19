package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/** Raised whenever a GitHub mutation was not explicitly approved. Throwing instead of merely
 * returning false makes the gate fail closed even if a tool provider accidentally ignores the
 * Boolean result: the mutation call stack is unwound before any network write can run. */
class GitHubMutationDeniedException(
    message: String = "GitHub mutation denied or confirmation unavailable"
) : IllegalStateException(message)

internal fun requireGitHubMutationApproved(approved: Boolean): Boolean {
    if (!approved) throw GitHubMutationDeniedException()
    return true
}

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
        // Concurrent confirmation requests are denied rather than sharing or replacing another
        // action's approval. Throwing is intentional: callers cannot accidentally continue.
        if (_pendingAction.value != null) throw GitHubMutationDeniedException("Another GitHub confirmation is already pending")

        val deferred = CompletableDeferred<Boolean>()
        val pending = PendingGitHubAction(repository, summary, deferred)
        _pendingAction.value = pending
        val approved = try {
            withTimeout(Constants.SHELL_CONFIRM_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            if (_pendingAction.value?.deferred === deferred) _pendingAction.value = null
        }

        // Outside tap, Back, explicit Deny, and timeout all resolve to false. Convert that result
        // into a hard stop so even a provider with a missing `return` cannot perform the mutation.
        return requireGitHubMutationApproved(approved)
    }

    /** Called by the UI. "Always allow" is persisted permanently across restarts and windows. */
    fun resolve(allow: Boolean, alwaysAllowRepository: Boolean = false) {
        val pending = _pendingAction.value ?: return
        if (allow && alwaysAllowRepository) addAllowed(pending.repository)
        pending.deferred.complete(allow)
        _pendingAction.value = null
    }
}
