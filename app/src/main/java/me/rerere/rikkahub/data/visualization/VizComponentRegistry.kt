package me.rerere.rikkahub.data.visualization

/** schema 与模型能力说明共用这张注册表，不接受动态类型或宿主代码。 */
object VizComponentRegistry {
    data class Fields(val required: Set<String>, val optional: Set<String> = emptySet(), val since: Int = 1)
    private fun fields(required: String, optional: String = "", since: Int = 1) = Fields(
        required.split(' ').filter(String::isNotEmpty).toSet(),
        optional.split(' ').filter(String::isNotEmpty).toSet(), since,
    )
    val nodes = linkedMapOf(
        "text" to fields("text"),
        "row" to fields("children"), "column" to fields("children"),
        "button" to fields("label action", "enabled"),
        "slider" to fields("label bind min max step"),
        "scene" to fields("width height shapes"),
        "status" to fields("text", "label tone", 3),
        "numberInput" to fields("label bind", "min max step", 3),
        "select" to fields("label bind options", since = 3),
        "toggle" to fields("label bind", since = 3),
        "stepper" to fields("bind count", "label resetOn", 3),
        "sequence" to fields("items", "mode label roles", 3),
        "table" to fields("columns rows", "label", 3),
    )
    val shapes = linkedMapOf(
        "rect" to fields("x y w h", "fill stroke sw r"),
        "line" to fields("x1 y1 x2 y2", "stroke sw"),
        "circle" to fields("cx cy r", "fill stroke sw"),
        "text" to fields("x y text", "size fill anchor"),
        "polyline" to fields("points", "stroke sw"),
        "path" to fields("d", "fill stroke sw"),
        "repeat" to fields("items shapes"),
        "plot" to fields("originX originY scaleX scaleY xMin xMax samples y", "stroke sw"),
        "labeledNode" to fields("x y w h label", "value subtitle fill stroke sw", 3),
        "connector" to fields("from to", "fromAnchor toAnchor label stroke sw arrow route", 3),
    )
    val slots = listOf("parameters", "main", "status", "controls")
    val operations = listOf("init", "state", "data", "appendData", "derived", "action", "shape", "replace",
        "remove", "node", "replaceNode", "removeNode", "done")
    fun description(): String = "视图：" + nodes.entries.joinToString("；") { (type, schema) ->
        "$type 必填(${schema.required.joinToString()}) 可选(${schema.optional.joinToString()})"
    } + "\n图元：" + shapes.entries.joinToString("；") { (type, schema) ->
        "$type 必填(${schema.required.joinToString()}) 可选(${schema.optional.joinToString()})"
    }
}
