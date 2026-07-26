package com.newoether.agora.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.newoether.agora.data.AutoBackupManager
import com.newoether.agora.data.MemoryManager
import com.newoether.agora.data.SettingsManager
import com.newoether.agora.api.local.LocalProvider
import com.newoether.agora.automation.TaskManager
import com.newoether.agora.automation.LoopManager
import com.newoether.agora.automation.ConversationExecutionCoordinator
import com.newoether.agora.automation.AutomationExecutionGate
import com.newoether.agora.tool.AutomationToolProvider
import com.newoether.agora.data.local.ChatDao
import com.newoether.agora.data.repository.ConversationRepository
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.sandbox.SandboxManagerFactory

class ChatViewModelFactory(
    private val application: Application,
    private val chatDao: ChatDao,
    private val settingsManager: SettingsManager,
    private val memoryManager: MemoryManager,
    private val context: Context,
    private val sandboxFactory: SandboxManagerFactory? = null,
    private val autoBackupManager: AutoBackupManager,
    private val conversationRepository: ConversationRepository,
    private val settingsRepository: SettingsRepository,
    private val localProvider: LocalProvider,
    private val providerRegistry: ProviderRegistry,
    private val taskManager: TaskManager,
    private val loopManager: LoopManager,
    private val automationToolProvider: AutomationToolProvider,
    private val conversationExecutionCoordinator: ConversationExecutionCoordinator,
    private val automationExecutionGate: AutomationExecutionGate,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(
                application, chatDao, settingsManager, memoryManager, context, sandboxFactory,
                autoBackupManager, conversationRepository, settingsRepository, localProvider, providerRegistry,
                taskManager, loopManager, automationToolProvider, conversationExecutionCoordinator,
                automationExecutionGate
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
