package me.rerere.rikkahub.data.graph

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class GraphPoint(val x: Double, val y: Double)
data class SampledCurve(val segments: List<List<GraphPoint>>, val evaluations: Int)

object FunctionSampler {
    const val MAX_EVALUATIONS = 8192
    const val MAX_GRAPH_EVALUATIONS = 131072

    suspend fun sample(
        curve: GraphCurve,
        expression: CompiledExpression,
        viewport: GraphViewport,
        basePoints: Int = 2048,
        maxEvaluations: Int = MAX_EVALUATIONS,
    ): SampledCurve {
        viewport.validate()
        val context = currentCoroutineContext()
        context.ensureActive()
        val domain = curve.domain
        val left = max(viewport.xMin, domain?.min ?: viewport.xMin)
        val right = min(viewport.xMax, domain?.max ?: viewport.xMax)
        if (left >= right) return SampledCurve(emptyList(), 0)
        val segments = mutableListOf<List<GraphPoint>>()
        var segment = mutableListOf<GraphPoint>()
        var evaluations = 0
        val budget = maxEvaluations.coerceIn(128, MAX_EVALUATIONS)
        val count = basePoints.coerceIn(32, min(2048, budget / 4))
        var intervalBudget = budget

        fun evaluate(x: Double): GraphPoint {
            context.ensureActive()
            evaluations++
            return GraphPoint(x, if (domain?.contains(x) == false) Double.NaN else expression.evaluate(x))
        }

        fun breakLine() {
            if (segment.isNotEmpty()) segments.add(segment.toList())
            segment = mutableListOf()
        }

        fun append(point: GraphPoint) {
            if (point.y.isFinite()) {
                if (segment.lastOrNull() != point) segment.add(point)
            } else breakLine()
        }

        fun refine(a: GraphPoint, b: GraphPoint, depth: Int) {
            context.ensureActive()
            if (evaluations >= intervalBudget) { breakLine(); append(b); return }
            val middle = evaluate(a.x + (b.x - a.x) / 2)
            val finite = a.y.isFinite() && b.y.isFinite() && middle.y.isFinite()
            val monotonic = finite && middle.y in min(a.y, b.y)..max(a.y, b.y)
            val outside = finite && (min(a.y, min(b.y, middle.y)) > viewport.yMax ||
                max(a.y, max(b.y, middle.y)) < viewport.yMin)
            if (outside && monotonic) { append(a); append(b); return }
            // 中点偏离弦线或非实数时细分；线性陡曲线保持连续，未确认连续时保守断线。
            val deviation = if (finite) abs(middle.y / 2 - (a.y / 4 + b.y / 4)) * 2 else Double.POSITIVE_INFINITY
            val suspicious = !finite || deviation > viewport.height / 600
            if (suspicious && depth < 7 && evaluations < intervalBudget - 2) {
                // 整段都无实数值无需再细分，避免空图耗尽自适应预算。
                if (!a.y.isFinite() && !b.y.isFinite() && !middle.y.isFinite()) { breakLine(); return }
                refine(a, middle, depth + 1)
                refine(middle, b, depth + 1)
            } else if (suspicious) {
                breakLine()
                append(b)
            } else {
                append(a)
                append(b)
            }
        }

        var previous = evaluate(left)
        append(previous)
        for (index in 1 until count) {
            // 为后续所有基础区间保留端点和中点预算，复杂局部不能截断整条曲线。
            intervalBudget = budget - (count - 1 - index) * 2
            val x = if (index == count - 1) right else left + (right - left) * index / (count - 1)
            val point = evaluate(x)
            refine(previous, point, 0)
            previous = point
        }
        breakLine()
        return SampledCurve(segments, evaluations)
    }
}
