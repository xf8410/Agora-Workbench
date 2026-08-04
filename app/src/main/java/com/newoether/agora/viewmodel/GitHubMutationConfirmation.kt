package com.newoether.agora.viewmodel

import java.lang.ref.WeakReference

/**
 * Weak process-local bridge for GitHub mutations. The first mutation can be approved once or with
 * the existing "always allow" choice; session approval then covers all GitHub mutations until the
 * app process ends. Read-only GitHub operations never enter this bridge.
 */
object GitHubMutationConfirmation {
    @Volatile private var controller = WeakReference<ShellConfirmationController>(null)

    fun register(value: ShellConfirmationController) {
        controller = WeakReference(value)
    }

    suspend fun confirm(summary: String): Boolean =
        controller.get()?.confirm("GitHub", summary) ?: false
}
