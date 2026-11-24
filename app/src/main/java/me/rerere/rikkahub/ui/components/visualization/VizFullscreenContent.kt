package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.visualization.number
import me.rerere.rikkahub.data.visualization.str

@Composable
internal fun VizFullscreenContent(
    session: VizSession,
    sendingDisabled: Boolean,
    presentation: VizPresentationState,
    sessionKey: String,
    onMessage: (String) -> Unit,
) {
    presentation.prepareSession(sessionKey, session)
    val panel = remember(presentation, sessionKey, session) { presentation.panel(sessionKey) }
    val frame = session.frame
    val projection = remember(frame) { projectFullscreen(frame) }
    val focus = LocalFocusManager.current
    // 动态显隐也属于展示转移；在旧控件卸载前使其失焦提交资格失效。
    val structure = remember(projection) {
        projection.groups.mapValues { (_, nodes) ->
            fun ids(node: me.rerere.rikkahub.data.visualization.VizNode): List<String?> =
                listOf(node.values["id"]?.str()) + node.children.flatMap(::ids)
            nodes.flatMap(::ids)
        }
    }
    remember(structure, projection.scenes.isNotEmpty()) { presentation.beginTransfer(sessionKey); true }
    LaunchedEffect(structure, projection.scenes.isNotEmpty()) { focus.clearFocus(force = true) }
    if (projection.scenes.isEmpty()) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            VizRenderer(session, sendingDisabled, presentation, sessionKey, interactive = true, onMessage = onMessage)
        }
        return
    }
    val scene = projection.scenes.firstOrNull { it.values["id"]?.str() == panel.sceneId }
        ?: projection.scenes[panel.sceneIndex.coerceIn(0, projection.scenes.lastIndex)]
    val identity = checkNotNull(scene.values["id"]?.str())
    SideEffect {
        panel.sceneId = identity
        panel.sceneIndex = projection.scenes.indexOf(scene)
    }
    val viewport = presentation.viewport(sessionKey, identity)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
        key(identity) {
            VizCanvas(scene, session.spec.title, sessionKey, presentation, identity,
                interactive = true, fillStage = true)
        }
        if (projection.scenes.size > 1) {
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.TopStart)) {
                FilledTonalButton(onClick = {
                    presentation.beginTransfer(sessionKey)
                    focus.clearFocus(force = true)
                    menu = true
                }) { Text("画布 ${projection.scenes.indexOf(scene) + 1} / ${projection.scenes.size}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    projection.scenes.forEachIndexed { index, node ->
                        DropdownMenuItem(text = { Text("画布 ${index + 1}") }, onClick = {
                            panel.sceneId = node.values["id"]?.str()
                            panel.sceneIndex = index
                            menu = false
                        })
                    }
                }
            }
        }
        Surface(Modifier.align(Alignment.TopEnd), shape = MaterialTheme.shapes.small, tonalElevation = 3.dp) {
            Column {
                TextButton(onClick = {
                    viewport.zoomBy(0.8f, scene.values.number("width").toFloat(),
                        scene.values.number("height").toFloat(), canvasSize)
                }) { Text("缩小") }
                TextButton(onClick = {
                    viewport.zoomBy(1.25f, scene.values.number("width").toFloat(),
                        scene.values.number("height").toFloat(), canvasSize)
                }) { Text("放大") }
                TextButton(onClick = { viewport.reset() }) { Text("复位") }
            }
        }
        if (projection.groups.isNotEmpty()) {
            VizFloatingControlPanel(panel, presentation, sessionKey, projection.groups.keys.toList()) { group ->
                CompositionLocalProvider(LocalVizControlPanel provides true) {
                VizRenderer(session, sendingDisabled, presentation, sessionKey, interactive = true,
                    nodes = projection.groups.getValue(group), onMessage = onMessage)
                }
            }
        }
    }
}
