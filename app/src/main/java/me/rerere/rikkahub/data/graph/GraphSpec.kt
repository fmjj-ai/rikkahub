package me.rerere.rikkahub.data.graph

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

const val PLOT_FUNCTION_TOOL_NAME = "plot_function"

@Serializable
data class GraphSpec(
    val version: Int = 1,
    val title: String = "函数图像",
    val curves: List<GraphCurve>,
    val viewport: GraphViewport = GraphViewport(),
    val showGrid: Boolean = true,
    val showLegend: Boolean = true,
)

@Serializable
data class GraphCurve(
    val id: String,
    val expression: String,
    val label: String = "",
    val domain: GraphDomain? = null,
    val points: List<GraphMarker> = emptyList(),
)

// 纵坐标由所属函数计算，避免标记与曲线使用不同的数值。
@Serializable
data class GraphMarker(val x: Double, val label: String = "")

@Serializable
data class GraphDomain(
    val min: Double,
    val max: Double,
    val includeMin: Boolean = true,
    val includeMax: Boolean = true,
) {
    fun contains(x: Double): Boolean =
        (x > min || includeMin && x == min) && (x < max || includeMax && x == max)
}

@Serializable
data class GraphRequest(val graphs: List<GraphSpec>)

@Serializable
data class GraphError(val code: String, val message: String)

@Serializable
data class GraphResult(
    val ok: Boolean,
    val graphs: List<GraphSpec> = emptyList(),
    val error: GraphError? = null,
)

class GraphValidationException(val code: String, message: String) : IllegalArgumentException(message)

object GraphProtocol {
    const val MAX_JSON_LENGTH = 30 * 1024
    const val MAX_CURVES = 50
    const val MAX_POINTS_PER_CURVE = 100
    const val MAX_POINTS_PER_GRAPH = 500
    val json = Json { encodeDefaults = true; explicitNulls = false }

    fun validate(graphs: List<GraphSpec>): List<GraphSpec> {
        checkLimit(graphs.size in 1..4, "每次需要 1–4 张图")
        return graphs.map { graph ->
            requireGraph(graph.version == 1, "UNSUPPORTED_VERSION", "不支持的绘图协议版本：${graph.version}")
            checkLimit(graph.title.length <= 120, "图标题最多 120 字符")
            checkLimit(graph.curves.size in 1..MAX_CURVES, "每张图需要 1–50 条曲线")
            checkLimit(graph.curves.sumOf { it.points.size } <= MAX_POINTS_PER_GRAPH, "每张图最多 500 个特殊点")
            graph.viewport.validate()
            requireGraph(graph.curves.map { it.id }.distinct().size == graph.curves.size,
                "INVALID_SPEC", "同一张图的曲线 id 不能重复")
            graph.copy(curves = graph.curves.map { curve ->
                checkLimit(curve.id.isNotBlank() && curve.id.length <= 40, "曲线 id 需要 1–40 字符")
                checkLimit(curve.label.length <= 128, "曲线标签最多 128 字符")
                curve.domain?.let {
                    requireGraph(it.min.isFinite() && it.max.isFinite() && it.min < it.max &&
                        abs(it.min) <= GraphViewport.MAX_COORDINATE && abs(it.max) <= GraphViewport.MAX_COORDINATE,
                        "INVALID_DOMAIN", "定义域需要有限边界，且 min < max（绝对值不超过 10¹²）")
                }
                val normalized = ExpressionCompiler.normalize(curve.expression)
                val expression = ExpressionCompiler.compile(normalized)
                checkLimit(curve.points.size <= MAX_POINTS_PER_CURVE, "每条曲线最多 100 个特殊点")
                curve.points.forEach { point ->
                    checkLimit(point.label.length <= 128, "特殊点标签最多 128 字符")
                    requireGraph(point.x.isFinite() && abs(point.x) <= GraphViewport.MAX_COORDINATE &&
                        curve.domain?.contains(point.x) != false && expression.evaluate(point.x).isFinite(),
                        "INVALID_POINT", "特殊点必须位于函数的有效定义域内，横坐标绝对值不超过 10¹²")
                }
                curve.copy(expression = normalized)
            })
        }
    }

    fun decodeResult(text: String): GraphResult {
        checkLimit(text.length <= MAX_JSON_LENGTH, "绘图数据过大")
        val result = json.decodeFromString<GraphResult>(text)
        return if (result.ok) result.copy(graphs = validate(result.graphs)) else result
    }

    private fun checkLimit(condition: Boolean, message: String) = requireGraph(condition, "RESOURCE_LIMIT", message)
}

internal fun requireGraph(condition: Boolean, code: String, message: String) {
    if (!condition) throw GraphValidationException(code, message)
}
