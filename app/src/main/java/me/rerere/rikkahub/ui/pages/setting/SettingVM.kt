package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(transform)
        }
    }

    // 需要等待保存成功再关闭页面的编辑器使用此入口。
    suspend fun saveSettings(transform: (Settings) -> Settings) {
        settingsStore.update(transform)
    }

    // 编辑器处理写盘失败时需要直接读取事实来源，不能依赖 stateIn 的异步回流。
    val latestSettings: Settings get() = settingsStore.settingsFlow.value
}
