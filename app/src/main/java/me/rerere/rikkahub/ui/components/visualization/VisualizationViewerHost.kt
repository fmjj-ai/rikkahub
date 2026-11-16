package me.rerere.rikkahub.ui.components.visualization

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.visualization.VizMessageEvent
import me.rerere.rikkahub.data.visualization.VizFenceScanner
import me.rerere.rikkahub.data.visualization.diagnosticReport

/**
 * 全屏查看器放在 ChatPage 的稳定宿主中。来源卡片可被 LazyColumn 回收，
 * 这里仍保留同一条 VizSession，并由页面负责在来源失效时关闭。
 */
@Composable
internal fun VisualizationViewerHost(
    active: VizActive?,
    store: VizSessionStore,
    loading: Boolean,
    sendingDisabled: Boolean,
    conversation: Conversation,
    presentation: VizPresentationState,
    onDismiss: () -> Unit,
    onDiagnosticCopy: (String) -> Unit,
    onDiagnosticFill: (String) -> Unit,
    onMessage: (VizMessageEvent) -> String?,
) {
    if (active == null) return
    val sessionKey = active.sessionKey
    var result by remember(sessionKey) { mutableStateOf(store.cached(sessionKey)) }
    var archive by remember(sessionKey) { mutableStateOf<VizDiagnosticArchive?>(null) }
    var showDiagnostics by remember(sessionKey) { mutableStateOf(false) }
    var showSource by remember(sessionKey) { mutableStateOf(false) }
    var menu by remember(sessionKey) { mutableStateOf(false) }
    var sending by remember(sessionKey) { mutableStateOf(false) }
    var diagnosticsBusy by remember(sessionKey) { mutableStateOf(false) }
    var sendError by remember(sessionKey) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val source = conversation.messageNodes.firstOrNull {
        "${it.currentMessage.id}:${it.selectIndex}" == active.messageIdentity
    }?.currentMessage?.parts?.getOrNull(active.partIndex) as? UIMessagePart.Text
    val latestSource by rememberUpdatedState(source?.text)
    val latestLoading by rememberUpdatedState(loading)
    var fence by remember(sessionKey) { mutableStateOf(active.fence) }

    DisposableEffect(sessionKey) {
        store.pin(sessionKey)
        onDispose { store.unpin(sessionKey) }
    }
    LaunchedEffect(sessionKey, store) {
        var previousSource = ""
        var previousFences = emptyList<me.rerere.rikkahub.data.visualization.VizSourceFence>()
        // 宿主独立订阅当前消息，卡片回收后仍可增量接收；不与卡片同时推进解析。
        snapshotFlow { latestSource to latestLoading }.conflate().collect { (text, isLoading) ->
            if (text == null || conversation.id.toString() != active.conversationId) {
                onDismiss()
                return@collect
            }
            val fences = withContext(Dispatchers.Default) {
                VizFenceScanner.advance(text, previousSource, previousFences)
            }
            previousSource = text
            previousFences = fences
            val current = fences.getOrNull(active.fence.ordinal)?.fence
            if (current == null || !current.language.equals(active.fence.language, true) ||
                (!active.streaming && current.code != active.fence.code)
            ) {
                onDismiss()
                return@collect
            }
            fence = current
            result = if (active.streaming) {
                store.advanceStreamAsync(sessionKey, active.messageIdentity, current.code, current.closed, isLoading) {
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { result = it }
                }
            } else store.load(sessionKey, active.messageIdentity, current.code)
        }
    }
    LaunchedEffect(sessionKey, result, showDiagnostics) {
        archive = if (showDiagnostics) {
            store.diagnosticSnapshot(sessionKey, active.messageIdentity, fence.code)
        } else null
    }
    LaunchedEffect(active.messageIdentity, conversation.messageNodes) {
        val exists = conversation.messageNodes.any {
            "${it.currentMessage.id}:${it.selectIndex}" == active.messageIdentity
        }
        if (!exists) onDismiss()
    }

    val session = result?.session
    val hasDiagnostics = (result?.diagnosticCount ?: 0) > 0 || result?.error != null
    fun copyDiagnostics() {
        if (diagnosticsBusy) return
        diagnosticsBusy = true
        scope.launch {
            try {
                store.diagnosticSnapshot(sessionKey, active.messageIdentity, fence.code)?.let {
                    diagnosticReport(it.items, it.count, it.fallbackError)?.let(onDiagnosticCopy)
                }
            } finally {
                diagnosticsBusy = false
            }
        }
    }
    fun fillDiagnostics() {
        if (diagnosticsBusy) return
        diagnosticsBusy = true
        scope.launch {
            try {
                store.diagnosticSnapshot(sessionKey, active.messageIdentity, fence.code)?.let {
                    diagnosticReport(it.items, it.count, it.fallbackError)?.let(onDiagnosticFill)
                }
            } finally {
                diagnosticsBusy = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.safeDrawingPadding().imePadding().padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        session?.spec?.title ?: "交互式可视化",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Box {
                        TextButton(onClick = { menu = true }) { Text("更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (session != null) {
                                DropdownMenuItem(
                                    text = { Text("重置参数") },
                                    onClick = { menu = false; session.enqueueReset() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (showSource) "返回图形" else "查看源码") },
                                onClick = { menu = false; showSource = !showSource },
                            )
                            DropdownMenuItem(text = { Text("复制源码") }, onClick = {
                                menu = false
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(fence.language, fence.code),
                                        ),
                                    )
                                }
                            })
                            if ((result?.diagnosticCount ?: 0) > 0) {
                                DropdownMenuItem(text = { Text("查看诊断") }, onClick = {
                                    menu = false
                                    showDiagnostics = !showDiagnostics
                                })
                            }
                            if (hasDiagnostics) {
                                DropdownMenuItem(text = { Text("复制诊断") }, onClick = {
                                    menu = false
                                    copyDiagnostics()
                                })
                                DropdownMenuItem(text = { Text("填入输入框") }, onClick = {
                                    menu = false
                                    fillDiagnostics()
                                })
                            }
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                if ((result?.diagnosticCount ?: 0) > 0) {
                    Text(
                        "部分内容未生成（${result?.diagnosticCount ?: 0}）",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (showDiagnostics) {
                        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            (archive?.items ?: result?.diagnostics.orEmpty()).forEach {
                                Text(it.summary(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    result?.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (hasDiagnostics) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { copyDiagnostics() }, enabled = !diagnosticsBusy) {
                            Text("复制诊断")
                        }
                        TextButton(onClick = { fillDiagnostics() }, enabled = !diagnosticsBusy) {
                            Text("填入输入框")
                        }
                    }
                }
                if (showSource) {
                    SelectionContainer {
                        Text(
                            fence.code,
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                } else if (session == null) {
                    Text(
                        if (loading && !fence.closed) "正在生成可视化…" else "可视化未完成，可查看源码",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(LocalVizCanvasHeight provides (maxHeight - 84.dp).coerceAtLeast(80.dp)) {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                VizRenderer(
                                    session = session,
                                    sendingDisabled = sendingDisabled || sending,
                                    presentation = presentation,
                                    sessionKey = sessionKey,
                                    interactive = true,
                                ) { action ->
                                    if (!sending && !sendingDisabled) {
                                        sending = true
                                        scope.launch {
                                            try {
                                                val text = session.message(action)
                                                sendError = onMessage(
                                                    VizMessageEvent(
                                                        java.util.UUID.randomUUID().toString(),
                                                        active.conversationId,
                                                        active.messageIdentity,
                                                        active.partIndex,
                                                        fence.ordinal,
                                                        VizFenceScanner.digest(fence.code),
                                                        text,
                                                    ),
                                                )
                                            } catch (failure: IllegalArgumentException) {
                                                sendError = failure.message
                                            } finally {
                                                sending = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                session?.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                sendError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
