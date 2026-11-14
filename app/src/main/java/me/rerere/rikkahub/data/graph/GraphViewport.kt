package me.rerere.rikkahub.data.graph

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

enum class GraphZoomAxis { BOTH, X, Y }

@Serializable
data class GraphViewport(
    val xMin: Double = -5.0,
    val xMax: Double = 5.0,
    val yMin: Double = -5.0,
    val yMax: Double = 5.0,
) {
    val width get() = xMax - xMin
    val height get() = yMax - yMin

    fun validate() {
        requireGraph(listOf(xMin, xMax, yMin, yMax).all { it.isFinite() && abs(it) <= MAX_COORDINATE } &&
            width in MIN_SPAN..MAX_SPAN && height in MIN_SPAN..MAX_SPAN,
            "INVALID_VIEWPORT", "视窗边界必须有限且递增，跨度为 10⁻⁶–10⁹，坐标绝对值不超过 10¹²")
    }

    // 焦点使用画布内的比例坐标；缩放轴共用有效倍率，触及边界时仍保持两轴的相对比例。
    fun transform(
        focusX: Double,
        focusY: Double,
        panX: Double,
        panY: Double,
        zoom: Double,
        axis: GraphZoomAxis = GraphZoomAxis.BOTH,
    ): GraphViewport {
        if (!zoom.isFinite() || zoom <= 0 || !panX.isFinite() || !panY.isFinite() ||
            !focusX.isFinite() || !focusY.isFinite()) return this
        val scaleX = axis != GraphZoomAxis.Y
        val scaleY = axis != GraphZoomAxis.X
        val minZoom = maxOf(if (scaleX) width / MAX_SPAN else 0.0, if (scaleY) height / MAX_SPAN else 0.0)
        val maxZoom = minOf(if (scaleX) width / minimumSpan(xMin, xMax) else Double.MAX_VALUE,
            if (scaleY) height / minimumSpan(yMin, yMax) else Double.MAX_VALUE)
        val effectiveZoom = zoom.coerceIn(minZoom, maxZoom.coerceAtLeast(minZoom))
        val newWidth = if (scaleX) width / effectiveZoom else width
        val newHeight = if (scaleY) height / effectiveZoom else height
        val fx = focusX.coerceIn(0.0, 1.0)
        val fy = focusY.coerceIn(0.0, 1.0)
        val left = (xMin + (width - newWidth) * fx - panX * newWidth)
            .coerceIn(-MAX_COORDINATE, MAX_COORDINATE - newWidth)
        val bottom = (yMin + (height - newHeight) * (1 - fy) + panY * newHeight)
            .coerceIn(-MAX_COORDINATE, MAX_COORDINATE - newHeight)
        val result = GraphViewport(left, if (!scaleX && panX == 0.0) xMax else left + newWidth,
            bottom, if (!scaleY && panY == 0.0) yMax else bottom + newHeight)
        // 巨幅平移后目标坐标的 ULP 可能变大，按目标精度联合放大跨度，避免端点重合。
        return if (result.width >= MIN_SPAN && result.height >= MIN_SPAN &&
            result.width <= MAX_SPAN && result.height <= MAX_SPAN) result
        else result.withCenteredSpans(newWidth, newHeight)
    }

    // 只改变指定轴的单位长度，另一轴范围不变；原点可见时固定原点，否则固定画布中心。
    fun stretch(axis: GraphZoomAxis, factor: Double): GraphViewport {
        val focusX = if (axis == GraphZoomAxis.X && 0.0 in xMin..xMax) -xMin / width else 0.5
        val focusY = if (axis == GraphZoomAxis.Y && 0.0 in yMin..yMax) yMax / height else 0.5
        return transform(focusX, focusY, 0.0, 0.0, factor, axis)
    }

    // 两轴一单位对应的像素长度是否一致；容差不足一个像素，但能吸收连续缩放平移累积的浮点误差。
    fun hasEqualUnits(pixelWidth: Int, pixelHeight: Int): Boolean {
        if (pixelWidth <= 0 || pixelHeight <= 0) return false
        val unitX = pixelWidth / width
        val unitY = pixelHeight / height
        return abs(unitX - unitY) <= maxOf(unitX, unitY) * 1e-4
    }

    // 扩展较短的数据范围，保留原视窗内容，让两轴一单位对应相同像素长度。
    fun fitAspectRatio(pixelWidth: Int, pixelHeight: Int): GraphViewport {
        if (pixelWidth <= 0 || pixelHeight <= 0) return this
        val unitsPerPixel = maxOf(width / pixelWidth, height / pixelHeight)
        return withCenteredSpans(unitsPerPixel * pixelWidth, unitsPerPixel * pixelHeight)
    }

    // 保持短边的可见跨度；旋转前后往返不会累积扩大视窗，也保留用户的单轴比例。
    fun resize(oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int): GraphViewport {
        if (oldWidth <= 0 || oldHeight <= 0) return fitAspectRatio(newWidth, newHeight)
        if (newWidth <= 0 || newHeight <= 0 || oldWidth == newWidth && oldHeight == newHeight) return this
        val shortRatio = minOf(oldWidth, oldHeight).toDouble() / minOf(newWidth, newHeight)
        return withCenteredSpans(width * newWidth / oldWidth * shortRatio,
            height * newHeight / oldHeight * shortRatio)
    }

    private fun withCenteredSpans(requestedWidth: Double, requestedHeight: Double): GraphViewport {
        if (!requestedWidth.isFinite() || !requestedHeight.isFinite()) return this
        val minScale = maxOf(minimumSpan(xMin, xMax) / requestedWidth, minimumSpan(yMin, yMax) / requestedHeight)
        val maxScale = minOf(MAX_SPAN / requestedWidth, MAX_SPAN / requestedHeight)
        // 极端比例超出浮点精度与协议范围时，优先返回可验证的有限视窗。
        val factor = if (minScale <= maxScale) 1.0.coerceIn(minScale, maxScale) else maxScale
        val newWidth = (requestedWidth * factor).coerceIn(minimumSpan(xMin, xMax), MAX_SPAN)
        val newHeight = (requestedHeight * factor).coerceIn(minimumSpan(yMin, yMax), MAX_SPAN)
        val left = (xMin + width / 2 - newWidth / 2).coerceIn(-MAX_COORDINATE, MAX_COORDINATE - newWidth)
        val bottom = (yMin + height / 2 - newHeight / 2).coerceIn(-MAX_COORDINATE, MAX_COORDINATE - newHeight)
        return GraphViewport(left, left + newWidth, bottom, bottom + newHeight)
    }

    companion object {
        const val MIN_SPAN = 1e-6
        const val MAX_SPAN = 1e9
        const val MAX_COORDINATE = 1e12

        private fun minimumSpan(min: Double, max: Double) = maxOf(MIN_SPAN * 1.01,
            Math.ulp(maxOf(abs(min), abs(max))) * 4)
    }
}

// 尺寸与视窗一起更新，手势、采样和点选始终使用同一份适配结果。
data class GraphViewportLayout(
    val viewport: GraphViewport,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
) {
    fun resize(width: Int, height: Int): GraphViewportLayout {
        if (width <= 0 || height <= 0) return this
        return GraphViewportLayout(viewport.resize(pixelWidth, pixelHeight, width, height), width, height)
    }

    // 横轴与纵轴单位长度之比，较短的一轴记为 1。
    fun unitRatioLabel(): String {
        if (pixelWidth <= 0 || pixelHeight <= 0 || viewport.hasEqualUnits(pixelWidth, pixelHeight)) return "1 : 1"
        val ratio = (pixelWidth / viewport.width) / (pixelHeight / viewport.height)
        fun format(value: Double) = if (value >= 1000) String.format(Locale.ROOT, "%.1e", value)
            else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        return if (ratio >= 1) "${format(ratio)} : 1" else "1 : ${format(1 / ratio)}"
    }
}

data class GraphAxisTicks(val x: List<Double>, val y: List<Double>, val xStep: Double, val yStep: Double)

// 单轴刻度数量上限；宽画布需要足够多的刻度，两轴共用步长后网格才不会过稀。
const val MAX_GRAPH_TICK_TARGET = 24

fun graphTickStep(span: Double, target: Int = 5): Double {
    val raw = span / target.coerceIn(2, MAX_GRAPH_TICK_TARGET)
    val magnitude = 10.0.pow(floor(log10(raw)))
    val fraction = raw / magnitude
    return magnitude * when {
        fraction <= 1 -> 1
        fraction <= 2 -> 2
        fraction <= 5 -> 5
        else -> 10
    }
}

fun graphTicks(min: Double, max: Double, target: Int = 5): List<Double> {
    if (!min.isFinite() || !max.isFinite() || min >= max) return emptyList()
    return graphTicksByStep(min, max, graphTickStep(max - min, target))
}

fun graphTicksByStep(min: Double, max: Double, step: Double): List<Double> {
    if (!min.isFinite() || !max.isFinite() || min >= max || !step.isFinite() || step <= 0) return emptyList()
    val start = ceil(min / step)
    return (0..MAX_GRAPH_TICK_TARGET).map { (start + it) * step }.takeWhile { it <= max }
}

// 两轴单位长度一致时共用较大的步长，网格为正方形；单轴拉长后各轴独立取步长。
fun graphAxisTicks(
    view: GraphViewport,
    pixelWidth: Int,
    pixelHeight: Int,
    xTarget: Int,
    yTarget: Int,
): GraphAxisTicks {
    var xStep = graphTickStep(view.width, xTarget)
    var yStep = graphTickStep(view.height, yTarget)
    if (view.hasEqualUnits(pixelWidth, pixelHeight)) {
        xStep = maxOf(xStep, yStep)
        yStep = xStep
    }
    return GraphAxisTicks(graphTicksByStep(view.xMin, view.xMax, xStep),
        graphTicksByStep(view.yMin, view.yMax, yStep), xStep, yStep)
}
