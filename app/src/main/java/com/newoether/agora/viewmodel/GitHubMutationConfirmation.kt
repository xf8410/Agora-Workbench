package com.newoether.agora.viewmodel

import java.lang.ref.WeakReference

/**
 * Weak, process-local bridge for critical GitHub mutations. A foreground ChatViewModel creates
 * [ShellConfirmationController], which registers itself here. Headless/background execution has no
 * live controller and therefore fails closed. Every call requires a fresh one-shot approval.
 */
object GitHubMutationConfirmation {
    @Volatile private var controller = WeakReference<ShellConfirmationController>(null)

    fun register(value: ShellConfirmationController) {
        controller = WeakReference(value)
    }

    suspend fun confirm(summary: String): Boolean =
        controller.get()?.confirmCritical("GitHub", summary) ?: false
}
