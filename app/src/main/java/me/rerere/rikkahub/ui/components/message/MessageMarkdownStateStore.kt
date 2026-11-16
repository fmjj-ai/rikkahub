package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.ui.components.richtext.*

internal data class MarkdownStateKey(
    val conversationId: String,
    val messageId: String,
    val partIndex: Int,
    val visualizations: Boolean,
    val regexes: List<AssistantRegex>,
)

internal data class MarkdownInput(val source: String, val precedingSources: List<String>)

/** 页面内的只读展示快照。每个 part 单消费者、合并待处理输入，绝不按 token 建缓存项。 */
internal class MessageMarkdownStateStore(
    private val scope: CoroutineScope,
    private val maxInactive: Int = 48,
    private val characterBudget: Long = 2L * 1024 * 1024,
    private val prepare: suspend (MarkdownInput, MarkdownPreparationState?, Boolean, (String) -> String) ->
        MarkdownPreparationState = { input, previous, visual, transform ->
            if (visual) prepareVisualMarkdown(input.source, input.precedingSources, previous, transform)
            else prepareMarkdownState(transform(input.source)).copy(source = input.source)
        },
) {
    private val entries = LinkedHashMap<MarkdownStateKey, Entry>(16, 0.75f, true)
    private var pinnedMessages = emptySet<String>()

    internal inner class Entry(
        val key: MarkdownStateKey,
        initial: MarkdownInput,
        private val transform: (String) -> String,
    ) {
        var snapshot by mutableStateOf(initialMarkdownFallback(initial.source, transform))
            private set
        var completedInput by mutableStateOf<MarkdownInput?>(null)
            private set
        var readers = 0
        private var closed = false
        private var requested: MarkdownInput? = null
        private var inFlight: MarkdownInput? = null
        private val inputs = Channel<MarkdownInput>(Channel.CONFLATED)
        private val worker = scope.launch {
            var parsed: MarkdownPreparationState? = null
            for (input in inputs) {
                inFlight = input
                val previous = snapshot
                val next = try {
                    prepare(input, parsed, key.visualizations, transform)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    initialMarkdownFallback(input.source, transform).copy(error = "正文解析失败，保留展示内容")
                }
                if (!closed) {
                    inFlight = null
                    if (next.prepared != null) parsed = next
                    // 不要求结果等于不断变化的最新输入；串行提交完整的中间快照，避免流式饥饿。
                    snapshot = if (next.prepared == null && previous.prepared != null) {
                        previous.copy(error = next.error)
                    } else next
                    completedInput = input
                    trim()
                }
            }
        }

        fun request(input: MarkdownInput) {
            if (!closed && requested != input) {
                requested = input
                inputs.trySend(input)
            }
        }

        fun close() {
            closed = true
            inputs.close()
            worker.cancel()
        }

        fun weight(): Long {
            val p = snapshot.prepared
            return snapshot.source.length.toLong() + snapshot.fallbackText.length +
                (p?.source?.length ?: 0) + (p?.preprocessed?.length ?: 0) +
                snapshot.sourceFences.sumOf { it.fence.code.length.toLong() } +
                (p?.let { (it.visualizationFences.values + it.overflowFences.values)
                    .sumOf { fence -> fence.code.length.toLong() } } ?: 0L) +
                snapshot.precedingCounts.sumOf { it.first.length.toLong() } +
                (requested?.source?.length ?: 0) +
                (requested?.precedingSources?.sumOf { it.length.toLong() } ?: 0L) +
                (completedInput?.source?.length ?: 0) +
                (completedInput?.precedingSources?.sumOf { it.length.toLong() } ?: 0L) +
                (inFlight?.source?.length ?: 0) +
                (inFlight?.precedingSources?.sumOf { it.length.toLong() } ?: 0L)
        }
    }

    fun entry(key: MarkdownStateKey, initial: MarkdownInput, transform: (String) -> String): Entry =
        entries.getOrPut(key) { Entry(key, initial, transform) }

    fun acquire(entry: Entry) { entry.readers++ }
    fun release(entry: Entry) { entry.readers--; trim() }

    fun retainConfiguration(key: MarkdownStateKey) {
        entries.keys.filter {
            it.conversationId == key.conversationId && it.messageId == key.messageId &&
                it.partIndex == key.partIndex && it != key
        }.forEach { entries.remove(it)?.close() }
    }

    fun retainParts(parts: Set<Pair<String, Int>>, pins: Set<String>) {
        pinnedMessages = pins
        entries.keys.filter { (it.messageId to it.partIndex) !in parts }.forEach { entries.remove(it)?.close() }
        trim()
    }

    private fun trim() {
        var weight = entries.values.sumOf { it.weight() }
        var inactive = entries.values.count { it.readers == 0 && it.key.messageId !in pinnedMessages }
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (inactive > maxInactive || weight > characterBudget)) {
            val entry = iterator.next().value
            if (entry.readers != 0 || entry.key.messageId in pinnedMessages) continue
            weight -= entry.weight()
            inactive--
            iterator.remove()
            entry.close()
        }
    }

    fun close() { entries.values.forEach { it.close() }; entries.clear() }
    internal val size get() = entries.size
}

internal val LocalMessageMarkdownStore = staticCompositionLocalOf<MessageMarkdownStateStore?> { null }
