package me.rerere.rikkahub.phoneuse

import android.os.Build
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.BuildConfig

/** 屏幕上一个可以操作或带文字的元素，[index] 在下一次 [PhoneController.captureScreen] 之前有效，坐标是屏幕像素 */
data class PhoneElement(
    val index: Int,
    val className: String,
    val label: String,
    /** 布局里的资源 ID 名（如 btn_send），应用没有设置或被系统隐藏时为空 */
    val resourceId: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val focused: Boolean,
)

data class PhoneScreen(
    val packageName: String?,
    val appLabel: String?,
    /** 这份元素来自哪些应用的窗口，[packageName] 只是最上层的那个 */
    val packages: Set<String>,
    val width: Int,
    val height: Int,
    val keyboardVisible: Boolean,
    val elements: List<PhoneElement>,
    /** 元素太多时只保留前面的一部分 */
    val truncated: Boolean,
)

data class PhoneApp(val packageName: String, val label: String)

enum class PhoneKey { BACK, HOME, RECENTS, NOTIFICATIONS, ENTER }

/**
 * 操作手机的底层能力，坐标都是屏幕像素。
 * 实现是 github 渠道的无障碍服务，用户在系统设置里开启后才存在；Play 版没有实现。
 */
interface PhoneController {
    /**
     * 屏幕上所有可见窗口所属的应用，和 [captureScreen] 读取的窗口范围一致，不算本应用自己的悬浮窗。
     * 分屏或下拉通知栏时不止一个；只用来判断屏幕上有谁，不影响元素编号
     */
    fun visiblePackages(): Set<String>

    /** 读取当前屏幕上的元素，不包含本应用自己的悬浮窗 */
    suspend fun captureScreen(): PhoneScreen

    /** JPEG 截图，长边不超过 [maxSide] */
    suspend fun screenshot(maxSide: Int): ByteArray

    /** 起点和终点相同时是点击，[durationMs] 决定是轻点还是长按 */
    suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean

    /** 把文字填进 [elementIndex] 指向的输入框；为空时填进当前有输入焦点的输入框 */
    suspend fun setText(elementIndex: Int?, text: String): Boolean

    suspend fun pressKey(key: PhoneKey): Boolean

    fun listApps(): List<PhoneApp>

    fun openApp(packageName: String): Boolean
}

/** 无障碍服务连接后把自己登记在这里，工具和设置页从这里拿 */
class PhoneUseManager {
    companion object {
        /** Play 版没有无障碍服务；截图和回车键是 Android 11 才有的接口，更低的版本不提供 */
        val isSupported: Boolean
            get() = !BuildConfig.IS_PLAY_STORE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    private val _controller = MutableStateFlow<PhoneController?>(null)
    val controller: StateFlow<PhoneController?> = _controller.asStateFlow()

    private val _acting = MutableStateFlow(false)

    /** 正在截图或执行手势，这期间悬浮窗要让出屏幕：不能出现在截图里，也不能挡住点击 */
    val acting: StateFlow<Boolean> = _acting.asStateFlow()

    private val executionMutex = Mutex()
    private val stateLock = Any()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private var activeOwner: Any? = null
    private var activeJob: Job? = null
    private var stopCallback: (() -> Unit)? = null
    private var stopEpoch = 0L

    // 无障碍服务只保存最近一次读屏节点；切换会话后不能复用另一会话的索引。
    internal var screenOwner: Any? = null

    suspend fun <T> execute(owner: Any, action: String, onStop: () -> Unit, block: suspend () -> T): T {
        val epoch = synchronized(stateLock) { stopEpoch }
        return executionMutex.withLock {
            val job = currentCoroutineContext()[Job]
            synchronized(stateLock) {
                // 停止时同时废弃已排队的操作，不能在当前操作取消后自动接着控制屏幕。
                if (epoch != stopEpoch) throw CancellationException("Phone Use stopped by user")
                activeOwner = owner
                activeJob = job
                stopCallback = onStop
                _status.value = action
            }
            try {
                withTimeout(30_000) { block() }
            } finally {
                synchronized(stateLock) {
                    activeJob = null
                    if (activeOwner == owner) _status.value = "等待模型下一步"
                }
                setActing(false)
            }
        }
    }

    fun finish(owner: Any) = synchronized(stateLock) {
        if (activeOwner == owner) {
            activeOwner = null
            activeJob = null
            stopCallback = null
            _status.value = null
            _acting.value = false
        }
    }

    fun stop() {
        val callback = synchronized(stateLock) {
            stopEpoch++
            activeJob?.cancel()
            activeOwner = null
            activeJob = null
            _status.value = null
            _acting.value = false
            stopCallback.also { stopCallback = null }
        }
        callback?.invoke()
    }

    fun attach(controller: PhoneController) {
        _controller.value = controller
    }

    fun detach(controller: PhoneController) {
        if (_controller.compareAndSet(controller, null)) {
            stop()
            screenOwner = null
        }
    }

    fun setActing(acting: Boolean) {
        _acting.value = acting
    }
}
