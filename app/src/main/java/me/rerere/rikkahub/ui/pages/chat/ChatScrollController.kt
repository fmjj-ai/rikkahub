package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/** 与会话一起保存的意图；旋转和返回页面不重新执行首次落底。 */
@Stable
internal class ChatScrollIntent(initialized: Boolean = false, following: Boolean = false) {
    var initialized by mutableStateOf(initialized)
    var following by mutableStateOf(following)

    companion object {
        val Saver = listSaver<ChatScrollIntent, Boolean>(
            save = { listOf(it.initialized, it.following) },
            restore = { ChatScrollIntent(it[0], it[1]) },
        )
    }
}

/** 定位以真实列表 key 为准；只有用户拖动或显式浏览操作才退出尾部跟随。 */
@Stable
internal class ChatScrollController(
    val state: LazyListState,
    private val scope: CoroutineScope,
    private val intent: ChatScrollIntent = ChatScrollIntent(),
) {
    internal data class Anchor(val key: String, val offset: Int, val index: Int)

    private var itemKeys: List<String?> = emptyList()
    private var messageCount = 0
    private var pendingTail = false
    private var tailFromSend = false
    private var generating = false
    private var requestVersion = 0L
    private var userMovedTowardTail = false
    private var userReachedTail = false
    private var userFling = false
    private var pendingTailMinMessages = 0
    private var pendingNode: String? = null
    private var pendingNodeOffset = 0
    private var autoScrollEnabled = true
    private var viewerOpen = false
    private var resumeAfterViewer: Anchor? = null
    private var viewerKeys: List<String?> = emptyList()
    private var syncJob: Job? = null
    private var settleJob: Job? = null
    private val listenerJob = scope.launch {
        state.interactionSource.interactions.collect { interaction ->
            // isScrollInProgress 也包含程序定位，不能据此判定用户上翻。
            if (interaction is DragInteraction.Start) onBrowse()
            if (interaction is DragInteraction.Stop) awaitUserScrollEnd()
            if (interaction is DragInteraction.Cancel) onBrowse()
        }
    }

    /** 只观察真实输入，不把程序定位、键盘避让和布局变化当成用户回底。 */
    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) {
                // 同一手势到达尾部后的越界位移不能抹掉真实到达证据；反向浏览则立即清除。
                val reached = userMovedTowardTail && userReachedTail && available.y < 0f
                onBrowse(towardTail = available.y < 0f)
                userReachedTail = reached
                awaitUserScrollEnd()
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if ((source == NestedScrollSource.UserInput || userFling) && consumed.y != 0f) {
                userMovedTowardTail = consumed.y < 0f
                userReachedTail = userMovedTowardTail && !state.canScrollForward &&
                    state.layoutInfo.visibleItemsInfo.any { it.key == ScrollTailKey }
                awaitUserScrollEnd()
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            userFling = userMovedTowardTail || available.y != 0f
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            userFling = false
            awaitUserScrollEnd()
            return Velocity.Zero
        }
    }

    private fun awaitUserScrollEnd() {
        settleJob?.cancel()
        settleJob = scope.launch {
            withFrameNanos { }
            snapshotFlow { state.isScrollInProgress }.first { !it }
            onUserScrollFinished()
        }
    }

    private fun cancelRequest() {
        requestVersion++
        syncJob?.cancel()
        syncJob = null
        settleJob?.cancel()
        settleJob = null
    }

    fun onSend(minMessages: Int = messageCount + 1) {
        intent.initialized = true
        cancelRequest()
        pendingTail = true
        tailFromSend = true
        pendingTailMinMessages = minMessages
        pendingNode = null
        intent.following = autoScrollEnabled
        resumeAfterViewer = null
        onLayoutChanged()
    }

    fun onInitialNode(nodeId: String) = onPreviewJump(nodeId)

    fun onInitialBottom() = onJumpToBottom()

    fun onPreviewJump(nodeId: String) {
        onBrowse()
        pendingNode = nodeId
        pendingNodeOffset = 0
        onLayoutChanged()
    }

    fun onJumpToBottom() {
        cancelRequest()
        pendingTail = true
        tailFromSend = false
        pendingTailMinMessages = 0
        pendingNode = null
        intent.following = autoScrollEnabled
        resumeAfterViewer = null
        onLayoutChanged()
    }

    fun onScrollTop() = onBrowse()

    fun onBrowse(towardTail: Boolean = false) {
        cancelRequest()
        userMovedTowardTail = towardTail
        userReachedTail = false
        userFling = false
        pendingTail = false
        pendingNode = null
        intent.following = false
        resumeAfterViewer = null
    }

    fun onGeneration(generating: Boolean) {
        if (this.generating && !generating) {
            cancelRequest()
            if (tailFromSend) pendingTail = false
            tailFromSend = false
        }
        this.generating = generating
        onLayoutChanged()
    }

    fun onUserScrollFinished(explicitUserScroll: Boolean = false) {
        if (state.isScrollInProgress || userFling) return
        if (userMovedTowardTail && (userReachedTail || explicitUserScroll) && !viewerOpen && !state.canScrollForward &&
            state.layoutInfo.visibleItemsInfo.any { it.key == ScrollTailKey }
        ) {
            intent.following = autoScrollEnabled
        }
        userMovedTowardTail = false
        userReachedTail = false
    }

    fun onViewer(open: Boolean) {
        if (viewerOpen == open) return
        viewerOpen = open
        if (open) {
            resumeAfterViewer = currentAnchor()
            viewerKeys = itemKeys
            cancelRequest()
        } else {
            val anchor = resumeAfterViewer
            if (!intent.following && anchor != null) {
                // 来源删除时选择原列表中最近的仍存活节点，避免跳回整段会话开头。
                pendingNode = anchor.key.takeIf { it in itemKeys } ?: viewerKeys.withIndex()
                    .filter { it.value != null && it.value in itemKeys }
                    .minByOrNull { kotlin.math.abs(it.index - anchor.index) }?.value
                pendingNodeOffset = if (pendingNode == anchor.key) anchor.offset else 0
            }
            resumeAfterViewer = null
            viewerKeys = emptyList()
            onLayoutChanged()
        }
    }

    fun onAutoScroll(enabled: Boolean) {
        autoScrollEnabled = enabled
        if (!enabled) intent.following = false
        onLayoutChanged()
    }

    /** 列表结构、生成资格和设置一次发布，避免先看到新布局却仍使用上一轮生成资格。 */
    fun onState(keys: List<String?>, messageCount: Int, generating: Boolean, autoScroll: Boolean) {
        itemKeys = keys
        this.messageCount = messageCount
        autoScrollEnabled = autoScroll
        if (!autoScroll) intent.following = false
        onGeneration(generating)
    }

    fun onSection(keys: List<String?>, messageCount: Int) {
        itemKeys = keys
        this.messageCount = messageCount
        onLayoutChanged()
    }

    private fun canFollow() = generating && autoScrollEnabled && intent.following && !viewerOpen

    /** 布局不是授权。结束后只允许尚未完成的显式定位，不再排队跟随。 */
    fun onLayoutChanged() {
        if (viewerOpen || syncJob?.isActive == true || state.isScrollInProgress) return
        if (pendingNode == null && !pendingTail && !canFollow()) return
        val version = requestVersion
        syncJob = scope.launch {
            withFrameNanos { }
            if (version != requestVersion || viewerOpen || state.isScrollInProgress) return@launch
            val node = pendingNode
            if (node != null) {
                if (scrollToKey(node, pendingNodeOffset)) pendingNode = null
                // 路由指定的节点尚未发布时保持待定位，不能以生成状态推断加载完成。
            } else if (pendingTail) {
                if (messageCount >= pendingTailMinMessages && scrollToKey(ScrollTailKey)) {
                    pendingTail = false
                }
            } else if (canFollow() && state.canScrollForward) {
                scrollToKey(ScrollTailKey)
            }
        }
    }

    private suspend fun scrollToKey(key: String, offset: Int = 0): Boolean {
        val index = itemKeys.indexOf(key)
        if (index < 0 || index >= state.layoutInfo.totalItemsCount) return false
        // 真实末项以正常 offset 定位会由 LazyColumn 自动夹到尾端，包含尾部 padding。
        state.scrollToItem(index, offset)
        return true
    }

    fun currentAnchor(): Anchor? {
        val index = state.firstVisibleItemIndex
        val key = itemKeys.getOrNull(index) ?: return null
        return Anchor(key, state.firstVisibleItemScrollOffset, index)
    }

    fun close() {
        cancelRequest()
        listenerJob.cancel()
    }

    companion object {
        const val ScrollTailKey = "ScrollBottomKey"
    }
}
