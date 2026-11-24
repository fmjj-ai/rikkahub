package me.rerere.rikkahub.ui.hooks

import kotlin.uuid.Uuid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant

@Composable
fun rememberAssistantState(
    settings: Settings,
    onSelectAssistant: (Uuid) -> Unit
): AssistantState {
    return remember(settings, onSelectAssistant) {
        AssistantState(settings, onSelectAssistant)
    }
}

class AssistantState(
    private val settings: Settings,
    private val onSelectAssistant: (Uuid) -> Unit
) {
    private var _currentAssistant by mutableStateOf(
        settings.getCurrentAssistant()
    )
    val currentAssistant get() = _currentAssistant

    fun setSelectAssistant(assistant: Assistant) {
        onSelectAssistant(assistant.id)
    }
}
