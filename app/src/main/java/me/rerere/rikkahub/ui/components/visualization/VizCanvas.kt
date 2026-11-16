package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.visualization.*

@Composable
internal fun VizCanvas(
    node: VizNode,
    title: String,
    sessionKey: String,
    presentation: VizPresentationState,
    identity: String,
    interactive: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val width = node.values.number("width").toFloat()
    val height = node.values.number("height").toFloat()
    val viewport = presentation.viewport(sessionKey, identity)
    val expandedHeight = LocalVizCanvasHeight.current
    val dispatcher = remember { NestedScrollDispatcher() }
    val nestedConnection = remember { object : NestedScrollConnection {} }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(viewport, width, height, canvasSize, interactive) {
        if (interactive) viewport.resize(width, height, canvasSize)
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val textStyle = MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val description = buildString {
        append(title)
        node.shapes.filter { it.type == "text" }.forEach {
            append("，")
            append(it.values.string("text"))
        }
    }
    fun color(token: String): Color = when (token) {
        "none" -> Color.Transparent
        "@primary" -> colors.primary
        "@onSurface" -> colors.onSurface
        "@surface" -> colors.surface
        "@outline" -> colors.outline
        "@success" -> if (dark) Color(0xFF93D49B) else Color(0xFF246A33)
        "@warning" -> if (dark) Color(0xFFFFCE72) else Color(0xFF815600)
        else -> Color((0xFF000000L or token.drop(1).toLong(16)).toInt())
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .then(if (interactive && expandedHeight != null) Modifier.height(expandedHeight)
                    else Modifier.aspectRatio(width / height))
                .clipToBounds()
                .then(
                    if (interactive) {
                        Modifier
                            .onSizeChanged { canvasSize = it }
                            .nestedScroll(nestedConnection, dispatcher)
                            .pointerInput(sessionKey, identity, width, height, canvasSize) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val consumed = viewport.transform(width, height, canvasSize, centroid, pan, zoom)
                                    // 适配视图不能平移时及放大后触边时，把剩余单指位移交给页面。
                                    if (zoom == 1f) dispatcher.dispatchPostScroll(
                                        consumed = consumed,
                                        available = pan - consumed,
                                        source = NestedScrollSource.UserInput,
                                    )
                                }
                            }
                            .pointerInput(viewport) {
                                detectTapGestures(onDoubleTap = { viewport.reset() })
                            }
                    } else {
                        Modifier.clickable(enabled = onOpen != null) { onOpen?.invoke() }
                    },
                )
                .semantics { contentDescription = description },
        ) {
            val scale = if (interactive) {
                viewport.scale(width, height, canvasSize)
            } else {
                minOf(size.width / width, size.height / height)
            }
            val offset = if (interactive) {
                viewport.offset(width, height, canvasSize)
            } else {
                Offset(
                    (size.width - width * scale) / 2f,
                    (size.height - height * scale) / 2f,
                )
            }
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                clipRect(0f, 0f, width, height) {
                    node.shapes.forEach { shape ->
                        val values = shape.values
                        fun n(name: String, default: Float = 0f): Float =
                            values[name]?.num()?.toFloat() ?: default
                        val fill = color(
                            values["fill"]?.str() ?: if (shape.type == "text") "@onSurface" else "none",
                        )
                        val stroke = color(values["stroke"]?.str() ?: "none")
                        val sw = n("sw", 1f)
                        when (shape.type) {
                            "rect" -> {
                                val origin = Offset(n("x"), n("y"))
                                val rectSize = Size(n("w"), n("h"))
                                val radius = CornerRadius(n("r"))
                                if (fill != Color.Transparent) drawRoundRect(fill, origin, rectSize, radius)
                                if (stroke != Color.Transparent && sw > 0) {
                                    drawRoundRect(stroke, origin, rectSize, radius, style = Stroke(sw))
                                }
                            }
                            "line" -> if (stroke != Color.Transparent && sw > 0) {
                                drawLine(stroke, Offset(n("x1"), n("y1")), Offset(n("x2"), n("y2")), sw)
                            }
                            "circle" -> {
                                val center = Offset(n("cx"), n("cy"))
                                if (fill != Color.Transparent) drawCircle(fill, n("r"), center)
                                if (stroke != Color.Transparent && sw > 0) {
                                    drawCircle(stroke, n("r"), center, style = Stroke(sw))
                                }
                            }
                            "text" -> if (fill != Color.Transparent) {
                                // scene 字号与坐标使用同一单位，避免密度转换两次及系统字号挤乱图形。
                                val layout = measurer.measure(
                                    text = values.string("text"),
                                    style = textStyle.copy(
                                        fontSize = (n("size", 12f) / density / fontScale).sp,
                                    ),
                                    softWrap = false,
                                )
                                val left = when (values["anchor"]?.str()) {
                                    "middle" -> n("x") - layout.size.width / 2f
                                    "end" -> n("x") - layout.size.width
                                    else -> n("x")
                                }
                                drawText(layout, fill, topLeft = Offset(left, n("y") - layout.firstBaseline))
                            }
                            "polyline", "plot" -> if (stroke != Color.Transparent && sw > 0) {
                                shape.paths.forEach { points ->
                                    if (points.size >= 2) {
                                        val path = Path()
                                        path.moveTo(points.first().x.toFloat(), points.first().y.toFloat())
                                        for (index in 1 until points.size) {
                                            path.lineTo(points[index].x.toFloat(), points[index].y.toFloat())
                                        }
                                        drawPath(path, stroke, style = Stroke(sw))
                                    }
                                }
                            }
                            "path" -> {
                                val path = Path()
                                shape.commands.forEach { command ->
                                    when (command.command) {
                                        'M' -> path.moveTo(command.x.toFloat(), command.y.toFloat())
                                        'L' -> path.lineTo(command.x.toFloat(), command.y.toFloat())
                                        'Z' -> path.close()
                                    }
                                }
                                if (fill != Color.Transparent) drawPath(path, fill)
                                if (stroke != Color.Transparent && sw > 0) {
                                    drawPath(path, stroke, style = Stroke(sw))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (interactive) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    onClick = { viewport.zoomBy(0.8f, width, height, canvasSize) },
                ) { Text("缩小") }
                TextButton(
                    onClick = { viewport.zoomBy(1.25f, width, height, canvasSize) },
                ) { Text("放大") }
                TextButton(onClick = { viewport.reset() }) { Text("复位") }
                Text(
                    "拖动平移 · 双指缩放 · 双击复位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
