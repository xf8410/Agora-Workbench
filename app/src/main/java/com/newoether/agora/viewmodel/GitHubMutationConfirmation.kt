package com.newoether.agora.viewmodel

import java.lang.ref.WeakReference

/**
 * Weak, process-local bridge for GitHub mutations executed by independently registered tool
 * providers. A foreground ChatViewModel creates [ShellConfirmationController], which registers
 * itself here. Headless/background execution has no live controller and therefore fails closed.
 */
object GitHubMutationConfirmation {
    @Volatile private var controller = WeakReference<ShellConfirmationController>(null)

    fun register(value: ShellConfirmationController) {
        controller = WeakReference(value)
    }

    suspend fun confirm(summary: String): Boolean =
        controller.get()?.confirm("GitHub", summary) ?: false
}
