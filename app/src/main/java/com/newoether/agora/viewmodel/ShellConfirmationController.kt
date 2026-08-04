package com.newoether.agora.viewmodel

import android.content.Context
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground confirmation controller shared by shell and GitHub mutations.
 *  "Always allow" is persisted across app restarts and shared across all conversations/windows. */
class ShellConfirmationController(
    context: Context,
    private val settings: SettingsRepository,
) {
    data class PendingShellCommand(
        val server: String,
        val summary: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private val _pendingShellCommand = MutableStateFlow<PendingShellCommand?>(null)
    val pendingShellCommand: StateFlow<PendingShellCommand?> = _pendingShellCommand.asStateFlow()

    private val prefs = context.getSharedPreferences("shell_confirmation", Context.MODE_PRIVATE)

    /** Servers/providers approved for all mutations — persisted across restarts. */
    private fun allowedSet(): Set<String> = prefs.getStringSet("allowed_servers", emptySet()) ?: emptySet()

    private fun addAllowed(server: String) {
        val current = allowedSet().toMutableSet()
        current.add(server)
        prefs.edit().putStringSet("allowed_servers", current).apply()
    }

    /** Revoke a server from the persistent allow-list (for settings UI). */
    fun revokeAllowed(server: String) {
        val current = allowedSet().toMutableSet()
        current.remove(server)
        prefs.edit().putStringSet("allowed_servers", current).apply()
    }

    /** Snapshot of all persistently approved servers (for settings UI). */
    fun allowedServers(): Set<String> = allowedSet()

    init {
        // Weak registration: this does not retain the ViewModel. Headless execution remains denied
        // until a foreground user has explicitly granted this process-wide GitHub session.
        GitHubMutationConfirmation.register(this)
    }

    /**
     * Read-only GitHub tools never call this method. On the first mutation the user can approve
     * once or choose "always allow"; the latter authorizes every later mutation permanently,
     * including branch writes, PR creation and merge, without repetitive prompts.
     */
    suspend fun confirm(server: String, summary: String): Boolean {
        if (!settings.shellConfirmEnabled.value) return true
        if (allowedSet().contains(server)) return true
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

    /** Called by the UI. "Always allow" is persisted permanently across restarts and windows. */
    fun resolve(allow: Boolean, alwaysAllowServer: Boolean = false) {
        val pending = _pendingShellCommand.value ?: return
        if (allow && alwaysAllowServer) addAllowed(pending.server)
        pending.deferred.complete(allow)
        _pendingShellCommand.value = null
    }

    fun setEnabled(enabled: Boolean) = settings.setShellConfirmEnabled(enabled)
}
