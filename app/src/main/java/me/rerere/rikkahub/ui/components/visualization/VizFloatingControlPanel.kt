package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Stable
internal class VizPanelState {
    var expanded by mutableStateOf(true)
    var width by mutableStateOf<Float?>(null)
    var height by mutableStateOf<Float?>(null)
    var xFraction by mutableFloatStateOf(1f)
    var yFraction by mutableFloatStateOf(1f)
    var group by mutableStateOf(VizControlGroup.Actions)
    var sceneId by mutableStateOf<String?>(null)
    var sceneIndex by mutableIntStateOf(0)
    fun resetGeometry() {
        width = null; height = null; xFraction = 1f; yFraction = 1f
    }
}

internal data class VizPanelBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/** 输入输出均为 dp；键盘或旋转只影响派生边界，不覆盖用户偏好尺寸。 */
internal fun panelBounds(state: VizPanelState, width: Float, height: Float): VizPanelBounds {
    val margin = minOf(12f, width / 4f, height / 4f).coerceAtLeast(0f)
    val w = (width - 2 * margin).coerceAtLeast(0f)
    val h = (height - 2 * margin).coerceAtLeast(0f)
    val actualW = if (state.expanded) (state.width ?: minOf(280f, 0.72f * width))
        .coerceIn(minOf(200f, w), minOf(560f, w)) else minOf(144f, w)
    val actualH = if (state.expanded) (state.height ?: minOf(220f, 0.32f * height))
        .coerceIn(minOf(144f, h), h) else minOf(48f, h)
    return VizPanelBounds(
        margin + (w - actualW) * state.xFraction.coerceIn(0f, 1f),
        margin + (h - actualH) * state.yFraction.coerceIn(0f, 1f), actualW, actualH,
    )
}

@Composable
internal fun VizFloatingControlPanel(
    state: VizPanelState,
    presentation: VizPresentationState,
    sessionKey: String,
    groups: List<VizControlGroup>,
    content: @Composable (VizControlGroup) -> Unit,
) {
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val selected = state.group.takeIf { it in groups } ?: groups.firstOrNull() ?: return
    SideEffect { state.group = selected }
    val scrolls = VizControlGroup.entries.associateWith { rememberScrollState() }
    fun transfer() {
        presentation.beginTransfer(sessionKey)
        focus.clearFocus(force = true)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val bounds = panelBounds(state, w, h)
        val margin = minOf(12f, w / 4f, h / 4f).coerceAtLeast(0f)
        fun move(x: Float, y: Float, bw: Float, bh: Float) {
            val dx = (w - 2 * margin - bw).coerceAtLeast(0f)
            val dy = (h - 2 * margin - bh).coerceAtLeast(0f)
            state.xFraction = if (dx > 0) ((x - margin) / dx).coerceIn(0f, 1f) else 0f
            state.yFraction = if (dy > 0) ((y - margin) / dy).coerceIn(0f, 1f) else 0f
        }
        Surface(
            modifier = Modifier.offset {
                with(density) { IntOffset(bounds.x.dp.roundToPx(), bounds.y.dp.roundToPx()) }
            }.size(bounds.width.dp, bounds.height.dp).clipToBounds()
                .pointerInput(Unit) {
                    // 参与命中即可阻挡下层兄弟画布；不抢占子控件的事件，避免取消点击和滑块拖动。
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Final)
                    }
                },
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Box {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().heightIn(max = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.weight(1f).height(48.dp).semantics { contentDescription = "拖动控制窗" }
                                .pointerInput(state, w, h, density) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        val b = panelBounds(state, w, h)
                                        move(b.x + drag.x / density.density, b.y + drag.y / density.density,
                                            b.width, b.height)
                                    }
                                }.padding(start = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text("控制窗", style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                        TextButton(onClick = { transfer(); state.expanded = !state.expanded }) {
                            Text(if (state.expanded) "收起" else "展开")
                        }
                    }
                    if (state.expanded) {
                        if (groups.size > 1) Row(Modifier.fillMaxWidth()) {
                            groups.forEach { group ->
                                TextButton(
                                    onClick = { if (group != selected) { transfer(); state.group = group } },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                ) {
                                    Text(group.title, color = if (group == selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(scrolls.getValue(selected))
                                .padding(start = 8.dp, end = 8.dp, bottom = 48.dp),
                        ) { key(selected) { content(selected) } }
                    }
                }
                if (state.expanded) Box(
                    Modifier.align(Alignment.BottomEnd).size(48.dp)
                        .semantics { contentDescription = "调整控制窗大小" }
                        .pointerInput(state, w, h, density) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val b = panelBounds(state, w, h)
                                val newW = (b.width + drag.x / density.density)
                                    .coerceIn(minOf(200f, w - 2 * margin), minOf(560f, w - 2 * margin))
                                val newH = (b.height + drag.y / density.density)
                                    .coerceIn(minOf(144f, h - 2 * margin), h - 2 * margin)
                                state.width = newW; state.height = newH
                                move(b.x, b.y, newW, newH)
                            }
                        },
                    contentAlignment = Alignment.BottomEnd,
                ) { Text("◢", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
