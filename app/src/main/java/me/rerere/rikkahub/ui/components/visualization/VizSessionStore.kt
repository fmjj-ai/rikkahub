package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.visualization.*

/** 每张卡片一条串行队列；流记录、输入、重置共享顺序，释放时取消全部待提交工作。 */
internal class VizQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init { scope.launch { for (task in tasks) task() } }
    suspend fun <T> submit(block: suspend () -> T): T {
        val reply = CompletableDeferred<T>(scope.coroutineContext[Job])
        tasks.send {
            try { reply.complete(block()) } catch (error: Throwable) { reply.completeExceptionally(error) }
        }
        return reply.await()
    }
    fun enqueue(block: () -> Unit) { tasks.trySend { block() } }
    fun close() { tasks.close(); scope.cancel() }
}

class VizSession internal constructor(initialSpec: VizSpec, private val queue: VizQueue = VizQueue()) {
    private val compiler = VizCompiler()
    private val defaultCompiler = VizCompiler()
    private data class Presentation(val spec: VizSpec, val frame: VizFrame)
    private var current by mutableStateOf(Presentation(initialSpec, VizEngine.frame(initialSpec, initialSpec.initialState, compiler)))
    val spec: VizSpec get() = current.spec
    val frame: VizFrame get() = current.frame
    var error by mutableStateOf<String?>(null)
        private set
    var resetRevision by mutableStateOf(0)
        private set
    @Volatile private var active = true

    fun enqueueDispatch(node: VizNode) = queue.enqueue { currentNode(node)?.let { dispatch(it) } }
    fun enqueueInput(node: VizNode, value: JsonElement) = queue.enqueue { currentNode(node)?.let { input(it, value) } }
    fun enqueueReset() = queue.enqueue { reset() }
    suspend fun message(action: String): String = queue.submit {
        check(active) { "图卡已失效" }
        VizEngine.message(spec, frame, action)
    }

    private fun currentNode(old: VizNode): VizNode? {
        val id = old.values["id"] ?: return null
        fun find(nodes: List<VizNode>): VizNode? {
            nodes.forEach { node ->
                if (node.values["id"] == id) return node
                find(node.children)?.let { return it }
            }
            return null
        }
        return find(frame.nodes)
    }

    @Synchronized
    fun dispatch(node: VizNode) {
        if (node.values["enabled"]?.bool() == false) return
        update { VizEngine.dispatch(spec, frame, node.values.string("action"), compiler) }
    }

    @Synchronized
    fun updateSlider(node: VizNode, value: Float) = input(node, JsonPrimitive(value.toDouble()))

    @Synchronized
    fun input(node: VizNode, value: JsonElement) = update { VizEngine.input(spec, frame, node, value, compiler) }

    private fun update(action: () -> VizFrame) {
        if (!active) return
        try {
            val next = action()
            Snapshot.withMutableSnapshot { if (active) { current = Presentation(spec, next); error = null } }
        } catch (failure: IllegalArgumentException) {
            Snapshot.withMutableSnapshot { error = failure.message ?: "操作失败，已保留原状态" }
        }
    }

    @Synchronized
    fun reset() {
        update { VizEngine.frame(spec, spec.initialState, compiler) }
        Snapshot.withMutableSnapshot { resetRevision++ }
    }

    /** 候选默认态/用户态同时合法才发布；记录永不覆盖已交互参数。 */
    @Synchronized
    fun updateSpec(next: VizSpec) {
        if (!active) throw VizException("图卡已失效")
        val nextState = JsonObject(next.initialState + frame.state)
        val defaults = VizEngine.frame(next, next.initialState,
            if (nextState == next.initialState) compiler else defaultCompiler)
        val nextFrame = if (nextState == next.initialState) defaults else VizEngine.frame(next, nextState, compiler, frame)
        Snapshot.withMutableSnapshot {
            if (!active) throw VizException("图卡已失效")
            current = Presentation(next, nextFrame)
            error = null
        }
    }

    internal fun close() { active = false }
}

data class VizLoadResult(
    val session: VizSession? = null,
    val error: String? = null,
    val done: Boolean = false,
    val diagnostics: List<VizDiagnostic> = emptyList(),
    val diagnosticCount: Int = 0,
    val lifecycle: VizLifecycle = VizLifecycle.RECEIVING,
)

internal data class VizDiagnosticArchive(
    val items: List<VizDiagnostic>,
    val count: Int,
    val fallbackError: String?,
)

/** 全局锁仅维护索引及 epoch；解析和构帧不持有其他卡片的锁。 */
class VizSessionStore(private val maxEntries: Int = 48) {
    private class Entry(val messageId: String, val queue: VizQueue = VizQueue()) {
        @Volatile var result = VizLoadResult()
        var stream = VizStreamState()
        val diagnostics = mutableListOf<VizDiagnostic>()
        var closed = false
        @Volatile var source = ""
        @Volatile var active = true
        fun close() { active = false; queue.close(); result.session?.close() }
    }
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val pinned = mutableMapOf<String, Int>()
    init { require(maxEntries > 0) }

    @Synchronized
    fun cached(key: String): VizLoadResult? = entries[key]?.result

    @Synchronized
    private fun entry(key: String, messageId: String, source: String, stream: Boolean): Entry {
        val old = entries[key]
        if (old != null && old.messageId == messageId &&
            (if (stream) source.startsWith(old.source) && (!old.closed || source == old.source) else old.source == source)) {
            old.source = source
            return old
        }
        old?.close()
        return Entry(messageId).also {
            it.source = source
            entries[key] = it
            while (entries.size > maxEntries) {
                val victim = entries.entries.firstOrNull { (key, _) -> (pinned[key] ?: 0) == 0 } ?: break
                victim.value.close()
                entries.remove(victim.key)
            }
        }
    }

    suspend fun load(key: String, messageId: String, source: String): VizLoadResult {
        val entry = entry(key, messageId, source, false)
        return entry.queue.submit {
            if (entry.result.session != null || entry.result.error != null) entry.result else {
                val result = try {
                    val spec = VizParser.parseElement(VizJsonReader(source).read().obj())
                    VizLoadResult(VizSession(spec, entry.queue), done = true, lifecycle = VizLifecycle.COMPLETE)
                } catch (error: IllegalArgumentException) {
                    VizLoadResult(error = error.message ?: "可视化内容无效", lifecycle = VizLifecycle.FAILED)
                }
                if (entry.active) {
                    entry.result = result
                }
                result
            }
        }
    }

    /** 累计文本推导新增行；首组件即时发布，每八行让出线程，不丢 conflate 合并的记录。 */
    suspend fun advanceStreamAsync(
        key: String, messageId: String, source: String, closed: Boolean, loading: Boolean,
        onProgress: (VizLoadResult) -> Unit,
    ): VizLoadResult {
        val entry = entry(key, messageId, source, true)
        return entry.queue.submit {
            var cursor = entry.stream.source.length
            var count = 0
            while (cursor < source.length && !entry.stream.failed && entry.active) {
                val newline = source.indexOf('\n', cursor)
                val end = if (newline < 0) source.length else newline + 1
                val hadContent = entry.result.session?.frame?.nodes?.isNotEmpty() == true
                advance(entry, source.substring(cursor, end))
                cursor = end
                count++
                if (!hadContent && entry.result.session?.frame?.nodes?.isNotEmpty() == true || count % 8 == 0) {
                    synchronized(this@VizSessionStore) {
                        if (entry.active && entries[key] === entry) onProgress(entry.result)
                    }
                    yield()
                }
            }
            if (entry.active) {
                finish(entry, closed, loading)
                synchronized(this@VizSessionStore) {
                    if (entry.active && entries[key] === entry) onProgress(entry.result)
                }
            }
            entry.result
        }
    }

    /** 同步入口供本地协议回归使用，UI 统一走串行后台队列。 */
    fun advanceStream(key: String, messageId: String, source: String, closed: Boolean): VizLoadResult {
        val entry = entry(key, messageId, source, true)
        return synchronized(entry) {
            if (source.length > entry.stream.source.length) advance(entry, source.substring(entry.stream.source.length))
            finish(entry, closed, true)
            entry.result
        }
    }

    private fun accept(entry: Entry, spec: VizSpec) {
        if (!entry.active) throw VizException("图卡已失效")
        val session = entry.result.session
        if (session == null) entry.result = entry.result.copy(session = VizSession(spec, entry.queue)) else session.updateSpec(spec)
    }

    private fun advance(entry: Entry, fragment: String) {
        entry.stream = VizStreamParser.advance(
            previous = entry.stream,
            fragment = fragment,
            onAcceptedSpec = { accept(entry, it) },
            onDiagnostic = { entry.diagnostics += it },
        )
        publish(entry)
    }

    private fun finish(entry: Entry, closed: Boolean, loading: Boolean) {
        if (closed && !entry.closed) {
            entry.stream = VizStreamParser.finish(
                previous = entry.stream,
                onAcceptedSpec = { accept(entry, it) },
                onDiagnostic = { entry.diagnostics += it },
            )
        }
        if (!loading && !closed && !entry.stream.failed && entry.stream.document?.done != true) {
            entry.stream = entry.stream.copy(lifecycle = VizLifecycle.CANCELLED)
        }
        entry.closed = closed
        publish(entry)
    }

    private fun publish(entry: Entry) {
        val state = entry.stream
        if (entry.active) {
            entry.result = VizLoadResult(entry.result.session, state.error,
                state.document?.done == true, state.diagnostics, state.diagnosticCount, state.lifecycle)
        }
    }

    @Synchronized
    fun retainMessages(messageIds: Set<String>) {
        entries.entries.removeAll {
            if (it.value.messageId !in messageIds && (pinned[it.key] ?: 0) == 0) {
                it.value.close()
                true
            } else false
        }
    }

    /** 全屏查看期间暂缓普通 LRU 淘汰；来源被移除或页面销毁时仍按原规则释放。 */
    @Synchronized
    fun pin(key: String) {
        pinned[key] = (pinned[key] ?: 0) + 1
    }

    @Synchronized
    fun unpin(key: String) {
        val count = (pinned[key] ?: 1) - 1
        if (count <= 0) pinned.remove(key) else pinned[key] = count
    }

    /** 在同一串行队列取快照，数量与正文来自同一次发布。 */
    internal suspend fun diagnosticSnapshot(
        key: String,
        messageId: String,
        expectedSource: String? = null,
    ): VizDiagnosticArchive? {
        val current = synchronized(this) {
            entries[key]?.takeIf {
                it.messageId == messageId && it.active &&
                    (expectedSource == null || it.source.startsWith(expectedSource))
            }
        } ?: return null
        return try {
            current.queue.submit {
                // 仅用户读取或展开诊断时复制全量，不在每条流记录发布时反复拷贝。
                synchronized(this@VizSessionStore) {
                    if (!current.active || entries[key] !== current) null else VizDiagnosticArchive(
                        items = current.diagnostics.toList(),
                        count = current.result.diagnosticCount,
                        fallbackError = current.result.error.takeIf { current.diagnostics.isEmpty() },
                    )
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { it.close() }
        entries.clear()
        pinned.clear()
    }
}
