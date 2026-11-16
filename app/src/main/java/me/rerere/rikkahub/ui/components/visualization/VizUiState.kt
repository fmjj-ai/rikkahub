package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import me.rerere.rikkahub.data.visualization.VizFence

/**
 * 卡片与全屏查看器轮流挂载同一个 renderer，交互草稿和视窗按来源保留，
 * 不把 renderer 同时组合两份。
 */
@Stable
internal class VizPresentationState {
    var location: VizLocation by mutableStateOf(VizLocation.Card)
        private set
    var active: VizActive? by mutableStateOf(null)
        private set

    private var numberDrafts = emptyMap<String, VizNumberDraft>()
    private var viewports = emptyMap<String, VizViewportState>()
    private val owners = linkedMapOf<String, String>()
    private val sessions = mutableMapOf<String, VizSession>()

    fun prepareSession(sessionKey: String, session: VizSession) {
        val previous = sessions.put(sessionKey, session)
        if (previous != null && previous !== session) {
            numberDrafts = numberDrafts.filterKeys { !it.startsWith("$sessionKey:") }
            viewports = viewports.filterKeys { !it.startsWith("$sessionKey:") }
        }
    }

    fun retainMessages(messageIds: Set<String>) {
        owners.filterValues { it !in messageIds }.keys.toList().forEach(::removeSession)
    }

    fun bindSession(sessionKey: String, messageId: String) {
        owners[sessionKey] = messageId
        while (owners.size > 48) {
            val victim = owners.keys.firstOrNull { it != active?.sessionKey } ?: break
            removeSession(victim)
        }
    }

    private fun removeSession(sessionKey: String) {
        owners.remove(sessionKey)
        sessions.remove(sessionKey)
        numberDrafts = numberDrafts.filterKeys { !it.startsWith("$sessionKey:") }
        viewports = viewports.filterKeys { !it.startsWith("$sessionKey:") }
    }

    fun isVisibleInCard(sessionKey: String): Boolean =
        location == VizLocation.Card && active?.sessionKey != sessionKey

    fun requestExpand(
        sessionKey: String,
        conversationId: String,
        messageIdentity: String,
        partIndex: Int,
        fence: VizFence,
        streaming: Boolean,
    ): VizActive? {
        val descriptor = VizActive(
            sessionKey = sessionKey,
            conversationId = conversationId,
            messageIdentity = messageIdentity,
            partIndex = partIndex,
            fence = fence,
            streaming = streaming,
        )
        if (active != null && active?.sessionKey != descriptor.sessionKey) return null
        active = descriptor
        location = VizLocation.Expanded
        return descriptor
    }

    fun updateActive(
        sessionKey: String,
        conversationId: String,
        messageIdentity: String,
        partIndex: Int,
        fence: VizFence,
        streaming: Boolean,
    ): VizActive? {
        val descriptor = VizActive(
            sessionKey = sessionKey,
            conversationId = conversationId,
            messageIdentity = messageIdentity,
            partIndex = partIndex,
            fence = fence,
            streaming = streaming,
        )
        if (active?.sessionKey != sessionKey) return null
        active = descriptor
        return descriptor
    }

    fun collapse() {
        active = null
        location = VizLocation.Card
    }

    fun dismissIf(conversationId: String?) {
        val current = active ?: return
        if (conversationId == null || current.conversationId != conversationId) {
            collapse()
        }
    }

    fun numberDraft(sessionKey: String, nodeId: String): VizNumberDraft? =
        numberDrafts["$sessionKey:$nodeId"]

    fun saveNumberDraft(sessionKey: String, nodeId: String, draft: VizNumberDraft) {
        numberDrafts = numberDrafts + ("$sessionKey:$nodeId" to draft)
    }

    fun clearNumberDraft(sessionKey: String, nodeId: String) {
        numberDrafts = numberDrafts - "$sessionKey:$nodeId"
    }

    fun viewport(sessionKey: String, identity: String): VizViewportState =
        viewports["$sessionKey:$identity"] ?: VizViewportState().also {
            viewports = viewports + ("$sessionKey:$identity" to it)
        }
}

internal enum class VizLocation { Card, Expanded, Closed }

internal data class VizActive(
    val sessionKey: String,
    val conversationId: String,
    val messageIdentity: String,
    val partIndex: Int,
    val fence: VizFence,
    val streaming: Boolean,
)

internal data class VizNumberDraft(
    val text: String,
    val focused: Boolean,
    val dirty: Boolean,
    val issue: String?,
    val resetRevision: Int,
)

internal data class VizDiagnosticActions(
    val copy: (String) -> Unit,
    val fill: (String) -> Unit,
)

internal val LocalVizPresentation = staticCompositionLocalOf<VizPresentationState?> { null }
internal val LocalVizDiagnosticActions = staticCompositionLocalOf<VizDiagnosticActions?> { null }
internal val LocalVizCanvasHeight = staticCompositionLocalOf<Dp?> { null }
