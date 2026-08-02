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

/** Foreground confirmation controller shared by shell and GitHub mutations. */
class ShellConfirmationController(private val settings: SettingsRepository) {
    data class PendingShellCommand(
        val server: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pendingShellCommand = MutableStateFlow<PendingShellCommand?>(null)
    val pendingShellCommand: StateFlow<PendingShellCommand?> = _pendingShellCommand.asStateFlow()

    /** Servers/providers approved for all mutations until this app process ends. */
    private val sessionAllowedServers = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        // Weak registration: this does not retain the ViewModel. Headless execution remains denied
        // until a foreground user has explicitly granted this process-wide GitHub session.
        GitHubMutationConfirmation.register(this)
    }

    /**
     * Read-only GitHub tools never call this method. On the first mutation the user can approve
     * once or choose "always allow"; the latter authorizes every later GitHub mutation in this app
     * process, including branch writes, PR creation and merge, without repetitive prompts.
     */
    suspend fun confirm(server: String, summary: String): Boolean {
        if (!settings.shellConfirmEnabled.value) return true
        if (sessionAllowedServers.contains(server)) return true
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

    /** Called by the UI. "Always allow" is process/session scoped, not permanently persisted. */
    fun resolve(allow: Boolean, alwaysAllowServer: Boolean = false) {
        val pending = _pendingShellCommand.value ?: return
        if (allow && alwaysAllowServer) sessionAllowedServers.add(pending.server)
        pending.deferred.complete(allow)
        _pendingShellCommand.value = null
    }

    fun setEnabled(enabled: Boolean) = settings.setShellConfirmEnabled(enabled)
}
