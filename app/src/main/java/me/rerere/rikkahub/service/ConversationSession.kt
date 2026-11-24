package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

data class EnglishSessionAIConfig(
    val modelId: Uuid? = null,
    val reasoningEnabled: Boolean = true,
)

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
    private val onIdle: (Uuid) -> Unit,
) {
    private val stateLock = Any()
    private val initializationMutex = Mutex()
    private val persistenceMutex = Mutex()
    private var initialized = false
    private val pendingInitialUpdates = mutableListOf<(Conversation) -> Conversation>()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> = _state.asStateFlow()
    val isInitialized: Boolean get() = synchronized(stateLock) { initialized }

    /** 并发首次读取合并；加载失败或取消后可以重试，局部修改意图不会丢失。 */
    suspend fun initialize(load: suspend () -> Conversation): Conversation {
        currentCoroutineContext().ensureActive()
        synchronized(stateLock) {
            if (initialized) return _state.value
        }
        return initializationMutex.withLock {
            synchronized(stateLock) {
                if (initialized) return@withLock _state.value
            }
            val loaded = try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (initialized) return@withLock _state.value
                }
                throw error
            }
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                // 显式完整替换可能在挂起加载期间发生，迟到结果不能再次覆盖它。
                if (!initialized) {
                    require(loaded.id == id)
                    val merged = pendingInitialUpdates.fold(loaded) { current, update ->
                        update(current).also { require(it.id == id) }
                    }
                    _state.value = merged
                    initialized = true
                    pendingInitialUpdates.clear()
                }
                _state.value
            }
        }
    }

    /** 变换必须无副作用；首次加载前即便看似没有变化，也保留字段赋值意图。 */
    fun update(transform: (Conversation) -> Conversation): Pair<Conversation, Conversation> =
        synchronized(stateLock) {
            val previous = _state.value
            val updated = transform(previous)
            require(updated.id == id)
            if (!initialized) pendingInitialUpdates.add(transform)
            _state.value = updated
            previous to updated
        }

    /** 用于已完整准备的会话，如发送结果、fork 或英语页显式新建，而不是局部字段更新。 */
    fun replace(conversation: Conversation): Pair<Conversation, Conversation> = synchronized(stateLock) {
        require(conversation.id == id)
        val previous = _state.value
        _state.value = conversation
        initialized = true
        pendingInitialUpdates.clear()
        previous to conversation
    }

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 英语学习页专用 AI 配置，只影响当前原生会话，不写入全局设置。
    @Volatile
    var englishAIConfig: EnglishSessionAIConfig? = null

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating || initializationMutex.isLocked

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        log("acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        log("release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend fun <T> withPersistenceLock(block: suspend () -> T): T = persistenceMutex.withLock { block() }

    /** 仅等待已经启动的加载；纯元数据操作不会由此读取完整历史。 */
    suspend fun awaitInitializationInProgress() {
        initializationMutex.withLock { }
    }

    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?) {
        _generationJob.value?.cancel()
        _generationJob.value = job
        job?.invokeOnCompletion {
            // 旧任务结束时不覆盖刚开始的新任务，否则压缩和发送可能同时运行。
            _generationJob.compareAndSet(job, null)
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!isInUse) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
