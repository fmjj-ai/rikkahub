package me.rerere.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(update: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.updateWithAppearanceCleanup(
                fileIdentity = filesManager::canonicalOwnedFileUrl,
                deleteFiles = { urls -> filesManager.deleteChatFiles(urls.map { it.toUri() }) },
                fn = update,
            )
        }
    }

    fun addAssistant(assistant: Assistant) {
        updateSettings { it.copy(assistants = it.assistants + assistant) }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            settingsStore.updateWithAppearanceCleanup(
                fileIdentity = filesManager::canonicalOwnedFileUrl,
                deleteFiles = { urls -> filesManager.deleteChatFiles(urls.map { it.toUri() }) },
            ) { latest -> latest.copy(assistants = latest.assistants.filterNot { it.id == assistant.id }) }
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }



    fun copyAssistant(assistant: Assistant) {
        val copiedId = kotlin.uuid.Uuid.random()
        updateSettings { latest ->
            val current = latest.assistants.find { it.id == assistant.id } ?: return@updateSettings latest
            val copied = current.copy(
                id = copiedId,
                name = current.name + " (Clone)",
                avatar = if (current.avatar is Avatar.Image) Avatar.Dummy else current.avatar,
            )
            latest.copy(assistants = latest.assistants + copied)
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
