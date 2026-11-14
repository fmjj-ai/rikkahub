package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.graph.GraphError
import me.rerere.rikkahub.data.graph.GraphProtocol
import me.rerere.rikkahub.data.graph.GraphRequest
import me.rerere.rikkahub.data.graph.GraphResult
import me.rerere.rikkahub.data.graph.GraphValidationException
import me.rerere.rikkahub.data.graph.PLOT_FUNCTION_TOOL_NAME

// 固定 schema 不依赖模型输出；可选字段省略时由版本化协议提供默认值。
private val graphToolProperties: JsonObject = GraphProtocol.json.parseToJsonElement(
    """
    {"graphs":{"type":"array","minItems":1,"maxItems":4,"items":{
      "type":"object","additionalProperties":false,"required":["version","curves","viewport"],
      "properties":{
        "version":{"type":"integer","enum":[1]},
        "title":{"type":"string","maxLength":120},
        "showGrid":{"type":"boolean"},"showLegend":{"type":"boolean"},
        "viewport":{"type":"object","additionalProperties":false,
          "required":["xMin","xMax","yMin","yMax"],"properties":{
            "xMin":{"type":"number"},"xMax":{"type":"number"},
            "yMin":{"type":"number"},"yMax":{"type":"number"}}},
        "curves":{"type":"array","minItems":1,"maxItems":50,"items":{
          "type":"object","additionalProperties":false,"required":["id","expression"],
          "properties":{
            "id":{"type":"string","minLength":1,"maxLength":40},
            "expression":{"type":"string","minLength":1,"maxLength":256},
            "label":{"type":"string","maxLength":128},
            "points":{"type":"array","maxItems":100,
              "description":"Optional special points on this curve (extrema, inflections, intercepts, endpoints, or user-selected points). Supply numeric x; y=f(x) is computed automatically. At most 500 points per graph.",
              "items":{"type":"object","additionalProperties":false,"required":["x"],
                "properties":{"x":{"type":"number"},"label":{"type":"string","maxLength":128}}}},
            "domain":{"type":"object","additionalProperties":false,"required":["min","max"],
              "properties":{"min":{"type":"number"},"max":{"type":"number"},
                "includeMin":{"type":"boolean"},"includeMax":{"type":"boolean"}}}
          }}}
      }}}}
    """.trimIndent()
).jsonObject

internal fun buildPlotFunctionTool(): Tool = Tool(
    name = PLOT_FUNCTION_TOOL_NAME,
    description = """
        Plot explicit mathematical functions y=f(x) as interactive offline coordinate graphs.
        Use only when a plot is requested or useful. For separate plots create separate graphs (1–4);
        each graph supports 1–50 curves. Use x, pi, e, + - * / ^, parentheses and
        sin cos tan asin acos atan sqrt abs exp ln log log10. Trigonometry uses radians;
        ln and log are natural logarithms; log10 is base 10. Multiplication must be explicit.
        Supply a finite increasing viewport; use domain for restricted intervals.
        Use a curve's points array to mark special points, chosen by you when useful or as requested
        by the user. Give each point a numeric x and an optional descriptive label; y is evaluated
        from that curve. Use points for extrema, inflections and other notable positions instead of
        creating tiny horizontal helper curves. Labels and titles support inline LaTeX delimiters;
        expressions are automatically typeset. Keep total serialized data within 30 KiB.
        Returns graph definitions, never images or executable code. No image model is needed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = graphToolProperties, required = listOf("graphs"), additionalProperties = false)
    },
    execute = { input ->
        val result = try {
            val text = input.toString()
            if (text.length > GraphProtocol.MAX_JSON_LENGTH) {
                throw GraphValidationException("RESOURCE_LIMIT", "绘图输入过大，请减少图数或缩短表达式")
            }
            val request = GraphProtocol.json.decodeFromString<GraphRequest>(text)
            GraphResult(ok = true, graphs = GraphProtocol.validate(request.graphs))
        } catch (error: GraphValidationException) {
            GraphResult(ok = false, error = GraphError(error.code, error.message ?: "绘图参数无效"))
        } catch (_: IllegalArgumentException) {
            GraphResult(ok = false, error = GraphError("INVALID_SPEC", "绘图 JSON 不符合协议，请检查字段类型和必要参数"))
        }
        val encoded = GraphProtocol.json.encodeToString(result)
        // JSON 转义可能扩大标题和标签体积，输出也必须限制以免历史结果被截断。
        val output = if (encoded.length <= GraphProtocol.MAX_JSON_LENGTH) encoded else {
            GraphProtocol.json.encodeToString(GraphResult(
                ok = false,
                error = GraphError("RESOURCE_LIMIT", "绘图结果过大，请缩短标题、标签或减少图数"),
            ))
        }
        listOf(UIMessagePart.Text(output))
    },
)
