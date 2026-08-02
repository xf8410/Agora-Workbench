package com.newoether.agora.viewmodel

import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/** Foreground confirmation controller shared by shell and fail-closed GitHub mutations. */
class ShellConfirmationController(private val settings: SettingsRepository) {
    data class PendingShellCommand(
        val server: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pendingShellCommand = MutableStateFlow<PendingShellCommand?>(null)
    val pendingShellCommand: StateFlow<PendingShellCommand?> = _pendingShellCommand.asStateFlow()
    private val sessionAllowedServers = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        // Weak registration: this does not retain the ViewModel. Headless execution remains denied.
        GitHubMutationConfirmation.register(this)
    }

    /** Ordinary shell policy: honors the user's setting and per-session trusted-server choice. */
    suspend fun confirm(server: String, summary: String): Boolean {
        if (!settings.shellConfirmEnabled.value) return true
        if (sessionAllowedServers.contains(server)) return true
        return awaitDecision(server, summary)
    }

    /**
     * Critical remote mutation policy. Never honors the shell-confirm toggle or session trust:
     * creating/merging a PR must receive a fresh foreground approval for that exact summary.
     */
    suspend fun confirmCritical(server: String, summary: String): Boolean =
        awaitDecision(server, summary)

    private suspend fun awaitDecision(server: String, summary: String): Boolean {
        // Do not replace another unresolved prompt: two concurrent mutations must not steal one
        // another's approval. The later operation fails closed and can be retried explicitly.
        if (_pendingShellCommand.value != null) return false
        val deferred = CompletableDeferred<Boolean>()
        val pending = PendingShellCommand(server, summary, deferred)
        _pendingShellCommand.value = pending
        return try {
            withTimeout(Constants.SHELL_CONFIRM_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            if (_pendingShellCommand.value === pending) _pendingShellCommand.value = null
        }
    }

    fun resolve(allow: Boolean, alwaysAllowServer: Boolean = false) {
        val pending = _pendingShellCommand.value ?: return
        // Critical GitHub confirmations are deliberately one-shot. The UI may still display the
        // existing checkbox, but it cannot authorize future GitHub PR creation/merge operations.
        if (allow && alwaysAllowServer && pending.server != "GitHub") {
            sessionAllowedServers.add(pending.server)
        }
        pending.deferred.complete(allow)
        _pendingShellCommand.value = null
    }

    fun setEnabled(enabled: Boolean) = settings.setShellConfirmEnabled(enabled)
}
