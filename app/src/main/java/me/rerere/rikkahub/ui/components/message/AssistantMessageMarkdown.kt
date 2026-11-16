package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.yield
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.visualization.VizFence
import me.rerere.rikkahub.ui.components.richtext.*
import me.rerere.rikkahub.ui.components.visualization.VisualizationBlock
import me.rerere.rikkahub.ui.components.visualization.VizSessionStore
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
internal fun AssistantMessageMarkdown(
    source: String,
    assistant: Assistant?,
    conversationId: String,
    messageId: String,
    partIndex: Int,
    store: VizSessionStore?,
    loading: Boolean,
    precedingSources: List<String>,
    isAssistant: Boolean,
    modifier: Modifier = Modifier,
    sendingDisabled: Boolean = loading,
    onMessage: ((me.rerere.rikkahub.data.visualization.VizMessageEvent) -> String?)? = null,
    onClickCitation: (String) -> Unit = {},
) {
    val enabled = isAssistant && LocalSettings.current.displaySetting.enableInlineVisualizations && store != null
    if (!enabled) {
        MarkdownBlock(
            source.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = true),
            modifier,
            onClickCitation = onClickCitation,
        )
        return
    }
    var prepared by remember(conversationId, messageId, partIndex) { mutableStateOf<MarkdownPreparationState?>(null) }
    val latest by rememberUpdatedState(Triple(source, precedingSources, assistant))
    LaunchedEffect(conversationId, messageId, partIndex) {
        var previous: MarkdownPreparationState? = null
        snapshotFlow { latest }.distinctUntilChanged().conflate().collect { (text, priorSources, currentAssistant) ->
            val next = prepareVisualMarkdown(text, priorSources, previous) {
                it.replaceRegexes(currentAssistant, AssistantAffectScope.ASSISTANT, visual = true)
            }
            previous = next
            prepared = next
            yield()
        }
    }
    val render: @Composable (VizFence) -> Unit = { fence ->
        VisualizationBlock(fence, "$conversationId:$messageId:$partIndex", messageId, store!!, loading,
            conversationId, partIndex, sendingDisabled, onMessage.takeIf { assistant?.enableInteractiveVisualization == true })
    }
    CompositionLocalProvider(LocalVizFenceRenderer provides render) {
        prepared?.let { PreparedMarkdownContent(it, modifier, onClickCitation = onClickCitation) }
    }
}
