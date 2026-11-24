package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.visualization.VizFence
import me.rerere.rikkahub.ui.components.richtext.*
import me.rerere.rikkahub.ui.components.visualization.VisualizationBlock
import me.rerere.rikkahub.ui.components.visualization.VizSessionStore

internal data class MessageMarkdownDisplay(val snapshot: MarkdownPreparationState, val settled: Boolean)

/** 在选择包装及气泡分支外调用，包装重挂载时同步提供同一展示快照。 */
@Composable
internal fun rememberMessageMarkdown(
    source: String,
    assistant: Assistant?,
    conversationId: String,
    messageId: String,
    partIndex: Int,
    precedingSources: List<String>,
    visualizations: Boolean,
): MessageMarkdownDisplay {
    val shared = LocalMessageMarkdownStore.current
    val scope = rememberCoroutineScope()
    val store = shared ?: remember { MessageMarkdownStateStore(scope) }
    if (shared == null) DisposableEffect(store) { onDispose { store.close() } }
    val regexes = assistant?.regexes.orEmpty().filter {
        it.enabled && it.visualOnly && AssistantAffectScope.ASSISTANT in it.affectingScope
    }
    val key = MarkdownStateKey(conversationId, messageId, partIndex, visualizations, regexes)
    val input = MarkdownInput(source, precedingSources)
    val lease = remember(store, key) {
        val rules = Assistant(regexes = regexes)
        val entry = store.entry(key, input) { it.replaceRegexes(rules, AssistantAffectScope.ASSISTANT, visual = true) }
        MarkdownEntryLease(store, entry)
    }
    val entry = lease.entry
    SideEffect {
        store.retainConfiguration(key)
        entry.request(input)
    }
    return MessageMarkdownDisplay(entry.snapshot, entry.completedInput == input)
}

/** 组合提交前即占用条目，避免另一旧分支释放时把即将显示的新条目当作历史淘汰。 */
private class MarkdownEntryLease(
    private val store: MessageMarkdownStateStore,
    val entry: MessageMarkdownStateStore.Entry,
) : RememberObserver {
    init { store.acquire(entry) }
    override fun onRemembered() = Unit
    override fun onForgotten() = store.release(entry)
    override fun onAbandoned() = store.release(entry)
}

/** 纯展示：此处不创建异步准备状态。 */
@Composable
internal fun AssistantMessageMarkdown(
    prepared: MarkdownPreparationState,
    assistant: Assistant?,
    conversationId: String,
    messageId: String,
    partIndex: Int,
    store: VizSessionStore?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    sendingDisabled: Boolean = loading,
    onMessage: ((me.rerere.rikkahub.data.visualization.VizMessageEvent) -> String?)? = null,
    onClickCitation: (String) -> Unit = {},
) {
    val render: (@Composable (VizFence) -> Unit)? = if (store == null) null else { fence ->
        VisualizationBlock(fence, "$conversationId:$messageId:$partIndex", messageId, store, loading,
            conversationId, partIndex, sendingDisabled, onMessage.takeIf { assistant?.enableInteractiveVisualization == true })
    }
    CompositionLocalProvider(LocalVizFenceRenderer provides render) {
        PreparedMarkdownContent(prepared, modifier, onClickCitation = onClickCitation)
    }
}
