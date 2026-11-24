package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*
import kotlin.math.*

/** 高层图形只展开为既有 Canvas 图元，不另开绘图或脚本通道。 */
internal object VizPrimitives {
    fun expand(type: String, values: JsonObject, anchors: Map<String, JsonObject>): List<JsonObject> =
        if (type == "labeledNode") labeledNode(values) else connector(values, anchors)

    private fun text(x: Double, y: Double, value: JsonElement, size: Int, fill: String) = buildJsonObject {
        put("type", "text"); put("x", x); put("y", y); put("text", value.displayText())
        put("anchor", "middle"); put("size", size); put("fill", fill)
    }

    private fun labeledNode(v: JsonObject): List<JsonObject> {
        val x = v.number("x"); val y = v.number("y"); val w = v.number("w"); val h = v.number("h")
        requireViz(w >= 24 && h >= 24, "带标签节点宽高至少为 24")
        val fill = v["fill"]?.str() ?: "@surface"
        val ink = if (fill.startsWith("#")) "#30343B" else "@onSurface"
        val result = mutableListOf(buildJsonObject {
            put("type", "rect"); put("x", x); put("y", y); put("w", w); put("h", h); put("r", min(10.0, h / 5))
            put("fill", fill); put("stroke", v["stroke"] ?: JsonPrimitive("@outline")); put("sw", v["sw"] ?: JsonPrimitive(1))
        })
        val hasValue = v.containsKey("value")
        result += text(x + w / 2, y + if (hasValue) h * 0.28 else h * 0.55, v.value("label"), 12, ink)
        if (hasValue) result += text(x + w / 2, y + h * 0.61, v.value("value"), 19, ink)
        v["subtitle"]?.let { result += text(x + w / 2, y + h * 0.86, it, 10, ink) }
        return result
    }

    private fun connector(v: JsonObject, anchors: Map<String, JsonObject>): List<JsonObject> {
        val from = anchors[v.string("from")] ?: throw VizException("起点不存在")
        val to = anchors[v.string("to")] ?: throw VizException("终点不存在")
        if (from["visible"]?.bool() == false || to["visible"]?.bool() == false) return emptyList()
        fun center(n: JsonObject) = VizPoint(n.number("x") + n.number("w") / 2, n.number("y") + n.number("h") / 2)
        val a = center(from); val b = center(to)
        val horizontal = abs(b.x - a.x) >= abs(b.y - a.y)
        val autoFrom = if (horizontal) if (b.x >= a.x) "right" else "left" else if (b.y >= a.y) "bottom" else "top"
        val autoTo = when (autoFrom) { "right" -> "left"; "left" -> "right"; "top" -> "bottom"; else -> "top" }
        fun anchor(node: JsonObject, requested: String?, auto: String): Pair<VizPoint, String> {
            val side = requested?.takeUnless { it == "auto" } ?: auto
            val c = center(node)
            return when (side) {
                "left" -> VizPoint(node.number("x"), c.y)
                "right" -> VizPoint(node.number("x") + node.number("w"), c.y)
                "top" -> VizPoint(c.x, node.number("y"))
                "bottom" -> VizPoint(c.x, node.number("y") + node.number("h"))
                else -> throw VizException("锚点无效")
            } to side
        }
        val (start, startSide) = anchor(from, v["fromAnchor"]?.str(), autoFrom)
        val (end, endSide) = anchor(to, v["toAnchor"]?.str(), autoTo)
        val points = mutableListOf(start)
        if (v["route"]?.str() != "straight") {
            val startHorizontal = startSide in setOf("left", "right")
            val endHorizontal = endSide in setOf("left", "right")
            when {
                startHorizontal && endHorizontal -> { points += VizPoint((start.x + end.x) / 2, start.y); points += VizPoint((start.x + end.x) / 2, end.y) }
                !startHorizontal && !endHorizontal -> { points += VizPoint(start.x, (start.y + end.y) / 2); points += VizPoint(end.x, (start.y + end.y) / 2) }
                startHorizontal -> points += VizPoint(end.x, start.y)
                else -> points += VizPoint(start.x, end.y)
            }
        }
        points += end
        val distinct = points.filterIndexed { index, point -> index == 0 || point != points[index - 1] }
        val stroke = v["stroke"] ?: JsonPrimitive("@outline")
        val sw = v["sw"] ?: JsonPrimitive(1.5)
        fun line(list: List<VizPoint>) = buildJsonObject {
            put("type", "polyline"); put("stroke", stroke); put("sw", sw)
            put("points", JsonArray(list.map { JsonArray(listOf(JsonPrimitive(it.x), JsonPrimitive(it.y))) }))
        }
        val result = mutableListOf(line(distinct))
        fun arrow(tip: VizPoint, previous: VizPoint) {
            val angle = atan2(tip.y - previous.y, tip.x - previous.x)
            val length = 7.0
            result += line(listOf(VizPoint(tip.x - length * cos(angle - 0.5), tip.y - length * sin(angle - 0.5)), tip,
                VizPoint(tip.x - length * cos(angle + 0.5), tip.y - length * sin(angle + 0.5))))
        }
        val arrowMode = v["arrow"]?.str() ?: "end"
        if (distinct.size >= 2) {
            if (arrowMode in setOf("start", "both")) arrow(distinct.first(), distinct[1])
            if (arrowMode in setOf("end", "both")) arrow(distinct.last(), distinct[distinct.lastIndex - 1])
        }
        v["label"]?.let { result += text((start.x + end.x) / 2, (start.y + end.y) / 2 - 5, it, 11, "@onSurface") }
        return result
    }
}
