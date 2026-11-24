package me.rerere.rikkahub.ui.components.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.graph.ExpressionCompiler
import me.rerere.rikkahub.data.graph.FunctionSampler
import me.rerere.rikkahub.data.graph.GraphPoint
import me.rerere.rikkahub.data.graph.GraphSpec
import me.rerere.rikkahub.data.graph.GraphViewport
import me.rerere.rikkahub.data.graph.GraphViewportLayout
import me.rerere.rikkahub.data.graph.GraphZoomAxis
import me.rerere.rikkahub.data.graph.MAX_GRAPH_TICK_TARGET
import me.rerere.rikkahub.data.graph.SampledCurve
import me.rerere.rikkahub.data.graph.GraphSelection
import me.rerere.rikkahub.data.graph.formatGraphCoordinate
import me.rerere.rikkahub.data.graph.graphExpressionLatex
import me.rerere.rikkahub.data.graph.graphTextParts
import me.rerere.rikkahub.data.graph.resolveGraphMarkers
import me.rerere.rikkahub.data.graph.selectGraphPoint
import me.rerere.rikkahub.data.graph.graphAxisTicks
import me.rerere.rikkahub.data.graph.graphTicks
import me.rerere.rikkahub.ui.components.richtext.MathInline

// 每次点击把对应轴的单位长度拉长 25%。
private const val AXIS_STRETCH_STEP = 1.25

@Composable
fun FunctionGraphCard(spec: GraphSpec, modifier: Modifier = Modifier) {
    var expanded by remember(spec) { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 卡片视窗由当前画布推导，尺寸或图定义变化后两轴单位仍然等长。
    val viewport = remember(spec, canvasSize) { spec.viewport.fitAspectRatio(canvasSize.width, canvasSize.height) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GraphFormattedText(spec.title, style = MaterialTheme.typography.titleMedium)
            Surface(onClick = { expanded = true }, shape = MaterialTheme.shapes.medium) {
                FunctionGraphCanvas(spec, viewport, Modifier.fillMaxWidth().height(240.dp)
                    .onSizeChanged { canvasSize = it })
            }
            if (spec.showLegend) {
                FunctionGraphLegend(spec, Modifier.heightIn(max = 180.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("弧度制 · 点击放大", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { expanded = true }) { Text("放大查看") }
            }
        }
    }
    if (expanded) FunctionGraphExpanded(spec, onDismiss = { expanded = false })
}

@Composable
fun FunctionGraphExpanded(spec: GraphSpec, onDismiss: () -> Unit) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // null 表示默认视图：始终按当前画布适配初始范围，两轴单位等长；手动调整后才保存独立视窗。
    var adjusted by remember(spec) { mutableStateOf<GraphViewportLayout?>(null) }
    var cursor by remember(spec) { mutableStateOf<GraphSelection?>(null) }
    // 每次调用都读取最新状态：手势协程不会用到过期的画布尺寸，同一帧内的连续增量也不会丢失。
    fun currentLayout() = adjusted
        ?: GraphViewportLayout(spec.viewport).resize(canvasSize.width, canvasSize.height)
    val layout = currentLayout()
    fun reset() {
        adjusted = null
        cursor = null
    }
    fun update(transform: (GraphViewportLayout) -> GraphViewport) {
        val current = currentLayout()
        if (current.pixelWidth <= 0 || current.pixelHeight <= 0) return
        val next = transform(current)
        if (next != current.viewport) {
            adjusted = current.copy(viewport = next)
            cursor = null
        }
    }
    fun zoom(factor: Double) = update { it.viewport.transform(0.5, 0.5, 0.0, 0.0, factor) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 画布以外的空白处双击同样恢复默认；按钮会消费点击，不会触发这里。
        Surface(Modifier.fillMaxSize().pointerInput(spec) { detectTapGestures(onDoubleTap = { reset() }) },
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GraphFormattedText(spec.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { reset() }) { Text("复位") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    FunctionGraphCanvas(
                        spec = spec,
                        viewport = layout.viewport,
                        selection = cursor,
                        onSelection = { cursor = it },
                        modifier = Modifier.fillMaxSize().onSizeChanged {
                            if (it != canvasSize) {
                                canvasSize = it
                                // 已调整的视图保留中心与短边尺度；默认视图按新尺寸重新适配初始范围。
                                adjusted = adjusted?.resize(it.width, it.height)
                                cursor = null
                            }
                        }.pointerInput(spec) {
                            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                                update { current ->
                                    current.viewport.transform(
                                        centroid.x.toDouble() / current.pixelWidth,
                                        centroid.y.toDouble() / current.pixelHeight,
                                        pan.x.toDouble() / current.pixelWidth,
                                        pan.y.toDouble() / current.pixelHeight,
                                        gestureZoom.toDouble(),
                                    )
                                }
                            }
                        },
                        onReset = { reset() },
                    )
                    // 底部留出横轴刻度标签的高度，按钮不遮挡刻度数字。
                    GraphAxisControls(layout.unitRatioLabel(),
                        Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 24.dp)) { axis ->
                        update { it.viewport.stretch(axis, AXIS_STRETCH_STEP) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("拖动平移 · 双指缩放 · 双击恢复默认\n点箭头拉长对应轴 · 点曲线读数", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { zoom(0.5) }) { Text("缩小") }
                    TextButton(onClick = { zoom(2.0) }) { Text("放大") }
                }
                if (spec.showLegend) {
                    FunctionGraphLegend(spec, Modifier.heightIn(max = 160.dp))
                }
            }
        }
    }
}

// 两个按钮都是一次性动作：每点一次把对应轴的单位长度拉长一档，不保留任何模式状态。
@Composable
private fun GraphAxisControls(ratioLabel: String, modifier: Modifier = Modifier, onStretch: (GraphZoomAxis) -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp, shadowElevation = 2.dp) {
        // 读数叠在按钮上方并靠右对齐：读数变长时按钮位置不动，窄屏上也少遮挡画布。
        Column(horizontalAlignment = Alignment.End) {
            Text("横纵比 $ratioLabel", Modifier.padding(start = 10.dp, top = 6.dp, end = 10.dp),
                style = MaterialTheme.typography.labelSmall)
            Row {
                listOf(GraphZoomAxis.X, GraphZoomAxis.Y).forEach { target ->
                    IconButton(
                        onClick = { onStretch(target) },
                        modifier = Modifier.semantics {
                            contentDescription = if (target == GraphZoomAxis.X) "拉长横轴" else "拉长纵轴"
                        },
                    ) {
                        val color = MaterialTheme.colorScheme.onSurface
                        Canvas(Modifier.size(24.dp)) {
                            val vertical = target == GraphZoomAxis.Y
                            fun point(x: Float, y: Float) = if (vertical) Offset(size.width * y, size.height * x)
                                else Offset(size.width * x, size.height * y)
                            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
                                color, point(x1, y1), point(x2, y2), 2.dp.toPx(), cap = StrokeCap.Round)
                            line(0.15f, 0.5f, 0.85f, 0.5f)
                            line(0.15f, 0.5f, 0.32f, 0.33f)
                            line(0.15f, 0.5f, 0.32f, 0.67f)
                            line(0.85f, 0.5f, 0.68f, 0.33f)
                            line(0.85f, 0.5f, 0.68f, 0.67f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun graphColors(): List<Color> = with(MaterialTheme.colorScheme) {
    listOf(primary, tertiary, secondary, error) + List(46) { index ->
        Color.hsv((index * 137.508f + 30f) % 360f, 0.65f, if (surface.red < 0.5f) 0.95f else 0.65f)
    }
}

@Composable
private fun GraphFormula(latex: String, modifier: Modifier = Modifier) {
    MathInline(latex, modifier.semantics { contentDescription = latex }.horizontalScroll(rememberScrollState()), fontSize = 14.sp)
}

@Composable
private fun GraphCurveFormula(expression: String) {
    val latex = remember(expression) { "y=${graphExpressionLatex(expression) ?: expression}" }
    GraphFormula(latex)
}

@Composable
private fun GraphFormattedText(text: String, modifier: Modifier = Modifier, style: TextStyle) {
    val parts = remember(text) { graphTextParts(text) }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        parts.forEach { part ->
            if (part.math) MathInline(part.text, Modifier.horizontalScroll(rememberScrollState()), fontSize = style.fontSize)
            else Text(part.text, style = style)
        }
    }
}

@Composable
private fun FunctionGraphLegend(spec: GraphSpec, modifier: Modifier = Modifier) {
    val colors = graphColors()
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(spec.curves, key = { _, curve -> curve.id }) { index, curve ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Canvas(Modifier.widthIn(min = 28.dp, max = 28.dp).height(12.dp)) {
                    drawLine(colors[index % colors.size], Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), pathEffect = curveEffect(index))
                }
                Text("${index + 1}.", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (curve.label.isNotBlank()) GraphFormattedText(curve.label, style = MaterialTheme.typography.bodySmall)
                    GraphCurveFormula(curve.expression)
                    curve.points.take(3).forEach { point ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("●", color = colors[index % colors.size], style = MaterialTheme.typography.labelSmall)
                            GraphFormattedText(point.label.ifBlank { "特殊点" } + " · x = ${formatCoordinate(point.x)}",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (curve.points.size > 3) Text("共 ${curve.points.size} 个特殊点，点击图上的点查看",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun curveEffect(index: Int): PathEffect? = when (index % 3) {
    1 -> PathEffect.dashPathEffect(floatArrayOf(12f, 6f))
    2 -> PathEffect.dashPathEffect(floatArrayOf(3f, 5f))
    else -> null
}

private data class GraphSamples(
    val viewport: GraphViewport,
    val curves: List<SampledCurve> = emptyList(),
    val error: String? = null,
)

@Composable
internal fun FunctionGraphCanvas(
    spec: GraphSpec,
    viewport: GraphViewport,
    modifier: Modifier = Modifier,
    selection: GraphSelection? = null,
    onReset: (() -> Unit)? = null,
    onSelection: ((GraphSelection?) -> Unit)? = null,
) {
    var samples by remember(spec) { mutableStateOf<GraphSamples?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 惰性编译只在后台首次使用时执行；每个任务复制求值器，变量表不共享。
    val compiled = remember(spec) { spec.curves.map { lazy { ExpressionCompiler.compile(it.expression) } } }
    LaunchedEffect(spec, viewport, canvasSize) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@LaunchedEffect
        val next = withContext(Dispatchers.Default) {
            try {
                val curves = spec.curves.mapIndexed { index, curve ->
                    FunctionSampler.sample(curve, compiled[index].value.fork(), viewport,
                        basePoints = (canvasSize.width * 2).coerceIn(256, 2048),
                        maxEvaluations = FunctionSampler.MAX_GRAPH_EVALUATIONS / spec.curves.size)
                }
                GraphSamples(viewport, curves)
            } catch (error: IllegalArgumentException) {
                GraphSamples(viewport, error = error.message ?: "绘图失败，请检查表达式")
            }
        }
        samples = next
    }
    val colors = graphColors()
    val scheme = MaterialTheme.colorScheme
    // 刻度标签在平移时大量重复，缓存需容纳两轴的全部标签。
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val labelStyle = TextStyle(color = scheme.onSurfaceVariant, fontSize = 10.sp)
    val current = samples
    val markers = remember(spec) { resolveGraphMarkers(spec) }
    val density = LocalDensity.current
    val sampling = current?.viewport != viewport
    val paths = remember(current, viewport, canvasSize) {
        current?.curves?.map { curve ->
            Path().apply {
                curve.segments.forEach { segment ->
                    var previousEnd: GraphPoint? = null
                    segment.zipWithNext().forEach { (a, b) ->
                        val clipped = clipGraphLine(a, b, viewport)
                        clipped?.let { (start, end) ->
                            fun px(x: Double) = ((x - viewport.xMin) / viewport.width * canvasSize.width).toFloat()
                            fun py(y: Double) = ((viewport.yMax - y) / viewport.height * canvasSize.height).toFloat()
                            if (previousEnd != start) moveTo(px(start.x), py(start.y))
                            lineTo(px(end.x), py(end.y))
                            previousEnd = end
                        }
                        if (clipped == null) previousEnd = null
                    }
                }
            }
        }.orEmpty()
    }
    val roughTicks = graphTicks(viewport.xMin, viewport.xMax)
    val roughStep = roughTicks.zipWithNext().firstOrNull()?.let { it.second - it.first } ?: viewport.width
    val labelWidth = roughTicks.maxOfOrNull {
        textMeasurer.measure(formatGraphCoordinate(it, roughStep), labelStyle).size.width
    } ?: 0
    val xTarget = (canvasSize.width / (labelWidth + with(density) { 24.dp.toPx() })).toInt()
        .coerceIn(2, MAX_GRAPH_TICK_TARGET)
    val yTarget = (canvasSize.height / with(density) { 32.dp.toPx() }).toInt().coerceIn(2, MAX_GRAPH_TICK_TARGET)
    // 两轴单位等长时共用刻度步长，网格为正方形，一眼可见横纵比例一致。
    val axisTicks = remember(viewport, canvasSize, xTarget, yTarget) {
        graphAxisTicks(viewport, canvasSize.width, canvasSize.height, xTarget, yTarget)
    }
    val xTicks = axisTicks.x
    val yTicks = axisTicks.y
    val xStep = axisTicks.xStep
    val yStep = axisTicks.yStep
    // 首帧尚未测量画布，传入的视窗还没按尺寸适配，此时只画背景，避免闪现比例不一致的坐标。
    val measured = canvasSize.width > 0 && canvasSize.height > 0
    val xLabels = xTicks.map { textMeasurer.measure(formatGraphCoordinate(it, xStep), labelStyle) }
    val yLabels = yTicks.map { textMeasurer.measure(formatGraphCoordinate(it, yStep), labelStyle) }
    val xAxisLabel = textMeasurer.measure("x", labelStyle)
    val yAxisLabel = textMeasurer.measure("y", labelStyle)
    val latestViewport by rememberUpdatedState(viewport)
    val latestSamples by rememberUpdatedState(current)
    val latestSelect by rememberUpdatedState(onSelection)
    val latestReset by rememberUpdatedState(onReset)
    val tapModifier = if (onSelection != null) Modifier.pointerInput(spec, canvasSize, density) {
        fun select(point: Offset) {
            val view = latestViewport
            val curves = latestSamples?.takeIf { it.viewport == view }?.curves.orEmpty()
            latestSelect?.invoke(selectGraphPoint(spec, view, curves, markers, compiled.map { it.value.fork() },
                point.x.toDouble(), point.y.toDouble(), canvasSize.width.toDouble(), canvasSize.height.toDouble(),
                with(density) { 24.dp.toPx().toDouble() }))
        }
        detectTapGestures(onDoubleTap = { latestReset?.invoke() }, onTap = { select(it) }, onLongPress = { select(it) })
    } else Modifier
    Box(modifier.then(tapModifier).onSizeChanged { canvasSize = it }.semantics {
        contentDescription = "${spec.title}，${spec.curves.joinToString { it.label.ifBlank { it.expression } }}；" +
            "x 范围 ${formatCoordinate(viewport.xMin)} 至 ${formatCoordinate(viewport.xMax)}，" +
            "y 范围 ${formatCoordinate(viewport.yMin)} 至 ${formatCoordinate(viewport.yMax)}"
    }) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(scheme.surface)
            if (!measured) return@Canvas
            fun px(x: Double) = ((x - viewport.xMin) / viewport.width * size.width).toFloat()
            fun py(y: Double) = ((viewport.yMax - y) / viewport.height * size.height).toFloat()
            clipRect {
                if (spec.showGrid) {
                    xTicks.forEach { x -> drawLine(scheme.outlineVariant, Offset(px(x), 0f), Offset(px(x), size.height)) }
                    yTicks.forEach { y -> drawLine(scheme.outlineVariant, Offset(0f, py(y)), Offset(size.width, py(y))) }
                }
                if (0.0 in viewport.xMin..viewport.xMax) {
                    drawLine(scheme.onSurfaceVariant, Offset(px(0.0), 0f), Offset(px(0.0), size.height), 1.dp.toPx())
                }
                if (0.0 in viewport.yMin..viewport.yMax) {
                    drawLine(scheme.onSurfaceVariant, Offset(0f, py(0.0)), Offset(size.width, py(0.0)), 1.dp.toPx())
                }
                paths.forEachIndexed { index, path ->
                    drawPath(path, colors[index % colors.size], style = Stroke(2.dp.toPx(), pathEffect = curveEffect(index)))
                }
                markers.forEach { marker ->
                    if (marker.point.x in viewport.xMin..viewport.xMax && marker.point.y in viewport.yMin..viewport.yMax) {
                        val point = Offset(px(marker.point.x), py(marker.point.y))
                        drawCircle(scheme.surface, 6.dp.toPx(), point)
                        drawCircle(colors[marker.curveIndex % colors.size], 4.dp.toPx(), point)
                    }
                }
                selection?.let {
                    val point = Offset(px(it.point.x), py(it.point.y))
                    drawLine(scheme.onSurface, Offset(point.x, 0f), Offset(point.x, size.height), pathEffect = curveEffect(1))
                    drawLine(scheme.onSurface, Offset(0f, point.y), Offset(size.width, point.y), pathEffect = curveEffect(1))
                    drawCircle(scheme.surface, 7.dp.toPx(), point)
                    drawCircle(it.curveIndex?.let { index -> colors[index % colors.size] } ?: scheme.primary, 5.dp.toPx(), point)
                }
                xTicks.forEachIndexed { index, value ->
                    val label = xLabels[index]
                    drawText(label, topLeft = Offset((px(value) - label.size.width / 2f).coerceIn(0f,
                        (size.width - label.size.width).coerceAtLeast(0f)), (size.height - label.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
                }
                yTicks.forEachIndexed { index, value ->
                    val label = yLabels[index]
                    drawText(label, topLeft = Offset(2.dp.toPx(), (py(value) - label.size.height / 2f).coerceIn(0f,
                        (size.height - label.size.height).coerceAtLeast(0f))))
                }
                drawText(xAxisLabel, topLeft = Offset((size.width - xAxisLabel.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                    (py(0.0).coerceIn(0f, size.height) - xAxisLabel.size.height - 4.dp.toPx()).coerceAtLeast(0f)))
                drawText(yAxisLabel, topLeft = Offset((px(0.0).coerceIn(0f, size.width) + 4.dp.toPx())
                    .coerceAtMost((size.width - yAxisLabel.size.width).coerceAtLeast(0f)), 4.dp.toPx()))
            }
        }
        selection?.let { selected ->
            var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
            Surface(
                modifier = Modifier.widthIn(max = 260.dp).offset {
                    val x = ((selected.point.x - viewport.xMin) / viewport.width * canvasSize.width).toInt()
                    val y = ((viewport.yMax - selected.point.y) / viewport.height * canvasSize.height).toInt()
                    val gap = with(density) { 16.dp.roundToPx() }
                    IntOffset(
                        (x - bubbleSize.width / 2).coerceIn(0, (canvasSize.width - bubbleSize.width).coerceAtLeast(0)),
                        (if (y - bubbleSize.height - gap >= 0) y - bubbleSize.height - gap else y + gap)
                            .coerceIn(0, (canvasSize.height - bubbleSize.height).coerceAtLeast(0)),
                    )
                }.onSizeChanged { bubbleSize = it },
                shape = MaterialTheme.shapes.small,
                color = scheme.primaryContainer,
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    val label = selected.markerLabel ?: selected.curveIndex?.let { index ->
                        spec.curves[index].label.ifBlank { "曲线 ${index + 1}" }
                    }
                    label?.let { GraphFormattedText(it, style = MaterialTheme.typography.labelMedium) }
                    selected.curveIndex?.let { GraphCurveFormula(spec.curves[it].expression) }
                    Text("(${formatCoordinate(selected.point.x)}, ${formatCoordinate(selected.point.y)})",
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (current == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else if (!sampling && (current.error != null || current.curves.all { curve ->
                curve.segments.none { points -> points.zipWithNext().any { (a, b) -> clipGraphLine(a, b, viewport) != null } }
            })) {
            Surface(Modifier.align(Alignment.Center).padding(16.dp), shape = MaterialTheme.shapes.small) {
                Text(current.error ?: "当前视窗没有可见的有效曲线", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (current != null && sampling) {
            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp), strokeWidth = 2.dp)
        }
    }
}

internal fun formatCoordinate(value: Double): String = formatGraphCoordinate(value)

// Liang–Barsky 线段裁剪：仅将视窗内的有限坐标传给 Canvas，避免陡曲线产生巨大 path。
internal fun clipGraphLine(a: GraphPoint, b: GraphPoint, view: GraphViewport): Pair<GraphPoint, GraphPoint>? {
    if (!a.y.isFinite() || !b.y.isFinite()) return null
    val dx = b.x - a.x
    val dy = b.y - a.y
    if (!dx.isFinite() || !dy.isFinite()) return null
    var low = 0.0
    var high = 1.0
    val p = doubleArrayOf(-dx, dx, -dy, dy)
    val q = doubleArrayOf(a.x - view.xMin, view.xMax - a.x, a.y - view.yMin, view.yMax - a.y)
    for (index in 0..3) {
        if (p[index] == 0.0) { if (q[index] < 0.0) return null }
        else {
            val ratio = q[index] / p[index]
            if (p[index] < 0.0) low = maxOf(low, ratio) else high = minOf(high, ratio)
            if (low > high) return null
        }
    }
    return GraphPoint(a.x + low * dx, a.y + low * dy) to GraphPoint(a.x + high * dx, a.y + high * dy)
}
