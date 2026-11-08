package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.datastore.cleanupAssistantTags
import me.rerere.rikkahub.data.datastore.updateAssistantTags
import me.rerere.rikkahub.data.datastore.updateAssistant
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings -> resolveDetailAssistant(settings, assistantId) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = resolveDetailAssistant(settingsStore.settingsFlow.value, assistantId),
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(transform: (List<Uuid>, List<Tag>) -> Pair<List<Uuid>, List<Tag>>) {
        viewModelScope.launch {
            settingsStore.update { it.updateAssistantTags(assistantId, transform) }
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch { settingsStore.update { it.cleanupAssistantTags() } }
    }

    fun update(transform: (Assistant) -> Assistant) {
        viewModelScope.launch {
            settingsStore.updateWithAppearanceCleanup(
                fileIdentity = filesManager::canonicalOwnedFileUrl,
                deleteFiles = { urls -> filesManager.deleteChatFiles(urls.map { it.toUri() }) },
            ) { latest -> latest.updateAssistant(assistantId, transform) }
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.addMemory(
                assistantId = memoryAssistantId,
                content = memory.content
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.updateContent(id = memory.id, content = memory.content)
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id = memory.id)
        }
    }




}

/** 首帧优先复用内存配置；缺失时仍保留路由 ID，避免共享头像 key 随机变化。 */
internal fun resolveDetailAssistant(settings: Settings, assistantId: Uuid): Assistant =
    settings.assistants.find { it.id == assistantId } ?: Assistant(id = assistantId)
