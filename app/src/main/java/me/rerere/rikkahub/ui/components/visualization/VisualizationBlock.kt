package me.rerere.rikkahub.ui.components.visualization

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.visualization.*

@Composable
internal fun VisualizationBlock(
    fence: VizFence,
    owner: String,
    messageId: String,
    store: VizSessionStore,
    loading: Boolean,
    conversationId: String = "",
    partIndex: Int = 0,
    sendingDisabled: Boolean = loading,
    onMessage: ((VizMessageEvent) -> String?)? = null,
) {
    val sharedPresentation = LocalVizPresentation.current
    val presentation = sharedPresentation ?: remember { VizPresentationState() }
    val actions = LocalVizDiagnosticActions.current
    val streaming = fence.language.equals("rikka-viz-stream", true)
    val sessionKey = if (streaming) "$owner:${fence.ordinal}:stream" else {
        "$owner:${fence.ordinal}:${remember(fence.code) { VizFenceScanner.digest(fence.code) }}"
    }
    var result by remember(sessionKey, store) { mutableStateOf(store.cached(sessionKey)) }
    var showSource by remember(sessionKey) { mutableStateOf(false) }
    var menu by remember(sessionKey) { mutableStateOf(false) }
    var showDiagnostics by remember(sessionKey) { mutableStateOf(false) }
    var sending by remember(sessionKey) { mutableStateOf(false) }
    var sendError by remember(sessionKey) { mutableStateOf<String?>(null) }
    var archive by remember(sessionKey) { mutableStateOf<VizDiagnosticArchive?>(null) }
    var diagnosticBusy by remember(sessionKey) { mutableStateOf(false) }
    var cardHeight by remember(sessionKey) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val focus = LocalFocusManager.current
    val latestFence by rememberUpdatedState(fence)
    val latestLoading by rememberUpdatedState(loading)
    val expanded = presentation.active?.sessionKey == sessionKey
    LaunchedEffect(presentation, sessionKey, messageId) {
        presentation.bindSession(sessionKey, messageId)
    }
    LaunchedEffect(sessionKey, store, expanded) {
        if (expanded) return@LaunchedEffect
        snapshotFlow { latestFence to latestLoading }.conflate().collect { (current, isLoading) ->
            result = if (streaming) {
                store.advanceStreamAsync(sessionKey, messageId, current.code, current.closed, isLoading) {
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { result = it }
                }
            } else if (current.closed) store.load(sessionKey, messageId, current.code) else null
        }
    }
    val session = result?.session

    fun copyDiagnostics() {
        if (diagnosticBusy) return
        diagnosticBusy = true
        scope.launch {
            try {
                val snapshot = store.diagnosticSnapshot(sessionKey, messageId, latestFence.code)
                val text = snapshot?.let { diagnosticReport(it.items, it.count, it.fallbackError) }
                if (text != null) actions?.copy?.invoke(text)
            } finally {
                diagnosticBusy = false
            }
        }
    }

    fun fillDiagnostics() {
        if (diagnosticBusy) return
        diagnosticBusy = true
        scope.launch {
            try {
                val snapshot = store.diagnosticSnapshot(sessionKey, messageId, latestFence.code)
                val text = snapshot?.let { diagnosticReport(it.items, it.count, it.fallbackError) }
                if (text != null) actions?.fill?.invoke(text)
            } finally {
                diagnosticBusy = false
            }
        }
    }

    LaunchedEffect(sessionKey, result, showDiagnostics) {
        archive = if (showDiagnostics) store.diagnosticSnapshot(sessionKey, messageId, latestFence.code) else null
    }

    fun expandCard() {
        presentation.requestExpand(
            sessionKey = sessionKey,
            conversationId = conversationId,
            messageIdentity = messageId,
            partIndex = partIndex,
            fence = fence,
            streaming = streaming,
        )
        focus.clearFocus(force = true)
    }

    if (expanded) {
        Surface(
            modifier = Modifier.fillMaxWidth().then(
                if (cardHeight > 0) Modifier.height(with(density) { cardHeight.toDp() }) else Modifier,
            ).padding(vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                Modifier.padding(12.dp).heightIn(min = 160.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text(
                    session?.spec?.title ?: "交互式可视化",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "正在全屏查看",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    DisableSelection {
        Surface(
            modifier = Modifier.fillMaxWidth().onSizeChanged { cardHeight = it.height }.padding(vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).clickable(enabled = session != null) { expandCard() }) {
                        Text(
                            session?.spec?.title ?: "交互式可视化",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    if (session != null) {
                        TextButton(onClick = { expandCard() }) { Text("放大") }
                    }
                    Box {
                        TextButton(onClick = {
                            presentation.beginTransfer(sessionKey)
                            focus.clearFocus(force = true)
                            menu = true
                        }) { Text("更多") }
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
                                        ClipEntry(ClipData.newPlainText(fence.language, fence.code)),
                                    )
                                }
                            })
                            if ((result?.diagnosticCount ?: 0) > 0) {
                                DropdownMenuItem(text = { Text("查看诊断") }, onClick = {
                                    menu = false
                                    showDiagnostics = !showDiagnostics
                                })
                            }
                            if ((result?.diagnosticCount ?: 0) > 0 || result?.error != null) {
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
                }
                if ((result?.diagnosticCount ?: 0) > 0) {
                    Text(
                        "部分内容未生成（${result?.diagnosticCount}）",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    result?.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (showDiagnostics) {
                    val diagnostics = archive?.items?.takeIf { it.isNotEmpty() }
                        ?: result?.diagnostics.orEmpty()
                    diagnostics.forEach {
                        Text(it.summary(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if ((result?.diagnosticCount ?: 0) > 0 || result?.error != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { copyDiagnostics() }, enabled = !diagnosticBusy) {
                            Text("复制诊断")
                        }
                        TextButton(onClick = { fillDiagnostics() }, enabled = !diagnosticBusy) {
                            Text("填入输入框")
                        }
                    }
                }
                if (session != null && !showSource) {
                    if (streaming && result?.done != true && result?.error == null) {
                        Text(
                            if (loading && !fence.closed) "正在生成 · 图形实时更新中" else "生成已停止，已保留当前画面",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    VizRenderer(
                        session = session,
                        sendingDisabled = sendingDisabled || sending || onMessage == null,
                        presentation = presentation,
                        sessionKey = sessionKey,
                        onOpen = { expandCard() },
                    ) { action ->
                        if (!sending && !sendingDisabled && onMessage != null) {
                            sending = true
                            scope.launch {
                                try {
                                    val text = session.message(action)
                                    sendError = onMessage(
                                        VizMessageEvent(
                                            java.util.UUID.randomUUID().toString(),
                                            conversationId,
                                            messageId,
                                            partIndex,
                                            fence.ordinal,
                                            VizFenceScanner.digest(latestFence.code),
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
                } else if (session == null && !showSource) {
                    Text(
                        if (loading && !fence.closed) "正在生成可视化…" else "可视化未完成，可查看源码",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                session?.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                sendError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (session?.spec?.actions?.values?.any { it.kind == "message" } == true && sendingDisabled) {
                    Text(
                        "回答生成期间暂不可发送，本地操作仍可用",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (showSource) {
                    SelectionContainer {
                        Text(
                            fence.code,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun VizRenderer(
    session: VizSession,
    sendingDisabled: Boolean,
    presentation: VizPresentationState,
    sessionKey: String,
    interactive: Boolean = false,
    onOpen: (() -> Unit)? = null,
    nodes: List<VizNode> = session.frame.nodes,
    onMessage: (String) -> Unit,
) {
    presentation.prepareSession(sessionKey, session)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        nodes.forEachIndexed { index, node ->
            key(node.values["id"]?.str() ?: "node:$index") {
                RenderNode(
                    session,
                    node,
                    sendingDisabled,
                    presentation,
                    sessionKey,
                    interactive,
                    onOpen,
                    onMessage,
                    index,
                )
            }
        }
    }
}

@Composable
private fun RenderNode(
    session: VizSession,
    node: VizNode,
    sendingDisabled: Boolean,
    presentation: VizPresentationState,
    sessionKey: String,
    interactive: Boolean,
    onOpen: (() -> Unit)?,
    onMessage: (String) -> Unit,
    index: Int,
) {
    node.error?.let {
        Text("此处更新失败：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    when (node.type) {
        "text" -> Text(node.values.string("text"))
        "row" -> FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            node.children.forEach { child ->
                key(child.values["id"]?.str()) {
                    RenderNode(
                        session, child, sendingDisabled, presentation, sessionKey,
                        interactive, onOpen, onMessage, child.values["id"]?.str()?.hashCode() ?: index,
                    )
                }
            }
        }
        "column" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { child ->
                key(child.values["id"]?.str()) {
                    RenderNode(
                        session, child, sendingDisabled, presentation, sessionKey,
                        interactive, onOpen, onMessage, child.values["id"]?.str()?.hashCode() ?: index,
                    )
                }
            }
        }
        "button" -> Button(
            onClick = {
                val action = node.values.string("action")
                if (session.spec.actions[action]?.kind == "message") {
                    onMessage(action)
                } else {
                    session.enqueueDispatch(node)
                }
            },
            enabled = node.values["enabled"]?.bool() != false &&
                !(sendingDisabled && session.spec.actions[node.values.string("action")]?.kind == "message"),
        ) {
            val label = node.values.string("label")
            Text(
                if (session.spec.actions[node.values.string("action")]?.kind == "message" &&
                    !label.contains("发送")
                ) "发送给 AI · $label" else label,
            )
        }
        "slider" -> {
            val value = session.frame.state.value(node.values.string("bind")).num()
            Column {
                Text(
                    "${node.values.string("label")}  " +
                        "%.6f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.'),
                )
                // 连续滑动交给引擎按 min/step 量化，避免非整除范围或大量刻度的二次吸附。
                Slider(
                    value = value.toFloat(),
                    onValueChange = { session.enqueueInput(node, JsonPrimitive(it.toDouble())) },
                    valueRange = node.values.number("min").toFloat()..node.values.number("max").toFloat(),
                )
            }
        }
        "scene" -> VizCanvas(
            node = node,
            title = session.spec.title,
            sessionKey = sessionKey,
            presentation = presentation,
            identity = node.values["id"]?.str() ?: "scene:$index",
            interactive = interactive,
            onOpen = onOpen,
        )
        else -> VizWidget(session, node, presentation, sessionKey)
    }
}
