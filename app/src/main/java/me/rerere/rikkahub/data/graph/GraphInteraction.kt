package me.rerere.rikkahub.data.graph

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.floor

data class GraphSelection(val point: GraphPoint, val curveIndex: Int? = null, val markerLabel: String? = null)
data class ResolvedGraphMarker(val point: GraphPoint, val curveIndex: Int, val label: String)

fun resolveGraphMarkers(spec: GraphSpec): List<ResolvedGraphMarker> = spec.curves.flatMapIndexed { index, curve ->
    if (curve.points.isEmpty()) emptyList() else {
        val expression = ExpressionCompiler.compile(curve.expression)
        curve.points.mapNotNull { marker ->
            val y = expression.evaluate(marker.x)
            if (y.isFinite() && curve.domain?.contains(marker.x) != false) {
                ResolvedGraphMarker(GraphPoint(marker.x, y), index, marker.label)
            } else null
        }
    }
}

// 所有命中距离均在屏幕像素空间计算，缩放或横竖屏不会改变触摸容差。
fun selectGraphPoint(
    spec: GraphSpec,
    viewport: GraphViewport,
    samples: List<SampledCurve>,
    markers: List<ResolvedGraphMarker>,
    expressions: List<CompiledExpression>,
    tapX: Double,
    tapY: Double,
    width: Double,
    height: Double,
    radius: Double,
): GraphSelection? {
    if (width <= 0 || height <= 0) return null
    fun px(point: GraphPoint) = (point.x - viewport.xMin) / viewport.width * width
    fun py(point: GraphPoint) = (viewport.yMax - point.y) / viewport.height * height
    fun visible(point: GraphPoint) = point.x in viewport.xMin..viewport.xMax && point.y in viewport.yMin..viewport.yMax
    markers.filter { visible(it.point) }.minByOrNull { hypot(px(it.point) - tapX, py(it.point) - tapY) }?.let {
        if (hypot(px(it.point) - tapX, py(it.point) - tapY) <= radius) {
            return GraphSelection(it.point, it.curveIndex, it.label.ifBlank { "特殊点" })
        }
    }
    var bestDistance = radius
    var selection: GraphSelection? = null
    samples.forEachIndexed { index, sampled ->
        sampled.segments.forEach { segment ->
            for (pointIndex in 1 until segment.size) {
                val a = segment[pointIndex - 1]
                val b = segment[pointIndex]
                val ax = px(a); val ay = py(a); val bx = px(b); val by = py(b)
                val dx = bx - ax; val dy = by - ay
                val length = dx * dx + dy * dy
                if (length.isFinite() && length > 0) {
                    val t = (((tapX - ax) * dx + (tapY - ay) * dy) / length).coerceIn(0.0, 1.0)
                    if (hypot(ax + t * dx - tapX, ay + t * dy - tapY) <= bestDistance) {
                        val x = a.x + t * (b.x - a.x)
                        val point = GraphPoint(x, expressions[index].evaluate(x))
                        val distance = hypot(px(point) - tapX, py(point) - tapY)
                        if (visible(point) && distance <= bestDistance && spec.curves[index].domain?.contains(x) != false) {
                            bestDistance = distance
                            selection = GraphSelection(point, index)
                        }
                    }
                }
            }
        }
    }
    return selection ?: GraphSelection(GraphPoint(
        viewport.xMin + tapX / width * viewport.width,
        viewport.yMax - tapY / height * viewport.height,
    ))
}

fun formatGraphCoordinate(value: Double, step: Double? = null): String {
    if (value == 0.0) return "0"
    // 大偏移下仍保留区分相邻刻度所需的有效位，避免所有标签变成同一个数字。
    val decimals = if (step != null && step > 0) (-floor(log10(step))).toInt().coerceIn(0, 14) else 6
    val formatted = if (abs(value) >= 1e7 || abs(value) < 1e-4) {
        val precision = if (step != null && step > 0) {
            (floor(log10(abs(value))) - floor(log10(step))).toInt().coerceIn(2, 15)
        } else 6
        String.format(Locale.ROOT, "%.${precision}e", value)
    } else String.format(Locale.ROOT, "%.${decimals}f", value).let {
        if ('.' in it) it.trimEnd('0').trimEnd('.') else it
    }
    return if (formatted == "-0" || formatted.isEmpty()) "0" else formatted
}
