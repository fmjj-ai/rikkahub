package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*

object VizParser {
    fun parse(source: String): VizSpec = parseElement(VizJsonReader(source).read().obj()).also {
        VizEngine.frame(it, it.initialState)
    }

    /** 只解码 schema；调用方负责默认态和当前态的事务校验。 */
    internal fun parseElement(root: JsonObject): VizSpec {
        val documentBytes = VizLimits.structure(root)
        val version = root.value("version").int()
        requireViz(version == 1 || version == 3, "不支持的可视化协议版本")
        root.fields(setOf("version", "id", "title", "state", "actions", "view"),
            if (version == 3) setOf("scene", "data", "derived") else emptySet())
        val id = root.string("id").also { validId(it, version) }
        val state = root.value("state").obj()
        requireViz(state.size <= VizLimits.STATE, "状态超过 32 项")
        state.forEach { (key, value) -> validId(key, 1); validateState(value) }
        val ids = mutableSetOf<String>()
        fun register(name: String) {
            validId(name, version)
            if (version == 3) requireViz(ids.add(name), "id 重复：$name")
        }
        val datasets = (root["data"]?.obj() ?: JsonObject(emptyMap())).mapValues { (name, value) ->
            register(name)
            val entry = value.obj()
            entry.fields(setOf("value"), setOf("inputKeys", "inputSnapshot"))
            requireViz((entry["inputKeys"] == null) == (entry["inputSnapshot"] == null), "轨迹输入声明必须成对提供")
            val inputKeys = entry["inputKeys"]?.arr()?.map { it.str() } ?: emptyList()
            requireViz(inputKeys.distinct().size == inputKeys.size && inputKeys.all { it in state }, "轨迹输入不存在或重复")
            val snapshot = entry["inputSnapshot"]?.obj() ?: JsonObject(emptyMap())
            requireViz(snapshot.keys == inputKeys.toSet(), "轨迹输入快照字段不匹配")
            inputKeys.forEach { requireViz(sameValueType(state.value(it), snapshot.value(it)), "轨迹输入类型不匹配") }
            VizDataset(entry.value("value"), inputKeys, snapshot)
        }
        requireViz(datasets.size <= VizLimits.DATA, "数据集超过 16 个")
        val derived = root["derived"]?.obj() ?: JsonObject(emptyMap())
        requireViz(derived.size <= VizLimits.DERIVED, "派生值超过 32 个")
        val declared = mutableSetOf<String>()
        derived.forEach { (name, expression) ->
            register(name)
            validateExpression(expression, state.keys, data = datasets.keys, derived = declared)
            declared += name
        }
        fun validate(value: JsonElement) = validateExpression(value, state.keys, data = datasets.keys, derived = declared)
        val actions = root.value("actions").obj()
        requireViz(actions.size <= VizLimits.ACTIONS, "动作超过 64 项")
        val parsedActions = actions.mapValues { (name, value) ->
            register(name)
            val action = value.obj()
            val kind = if (version == 3) action.string("kind") else "local"
            action["when"]?.let(::validate)
            when (kind) {
                "local" -> {
                    action.fields(setOf("set") + if (version == 3) setOf("kind") else emptySet(), setOf("when"))
                    val assignments = action.value("set").obj()
                    requireViz(assignments.keys.all { it in state }, "动作写入了未声明状态")
                    assignments.values.forEach(::validate)
                    VizAction(action["when"], assignments)
                }
                "message" -> {
                    action.fields(setOf("kind", "prompt", "include"), setOf("when"))
                    val include = action.value("include").arr().map { it.str() }
                    requireViz(include.distinct().size == include.size && include.all { it in state }, "发送参数不存在或重复")
                    requireViz(action.string("prompt").isNotBlank(), "发送提示不能为空")
                    VizAction(action["when"], kind = kind, prompt = action.string("prompt"), include = include)
                }
                else -> throw VizException("未知动作类型：$kind")
            }
        }
        val title = root.string("title")
        requireViz(title.length <= 256, "标题超过 256 字符")
        val view = root.value("view").arr().map { it.obj() }
        val validator = SchemaValidator(version, state.keys, parsedActions.keys, datasets.keys, declared, ::register)
        view.forEach { validator.view(it, true) }
        val scene = root["scene"]?.obj()
        scene?.let { validator.view(it, false) }
        return VizSpec(id, title, state, parsedActions, view, version, scene, datasets, derived,
            documentBytes, VizLimits.structure(state))
    }

    internal fun validId(id: String, version: Int = 3) {
        requireViz(id.isNotBlank() && id.length <= 64 && (version < 3 || !id.startsWith("__")),
            "id 必须为 1–64 个字符，且不能使用保留前缀 __")
    }

    internal fun validateState(value: JsonElement, depth: Int = 0) {
        requireViz(depth <= VizLimits.JSON_DEPTH, "状态嵌套过深")
        when (value) {
            is JsonObject -> {
                requireViz(value.keys != setOf("$"), "初始状态只能包含字面量")
                value.values.forEach { validateState(it, depth + 1) }
            }
            is JsonArray -> {
                requireViz(value.size <= VizLimits.ARRAY, "状态数组超过 256 项")
                value.forEach { requireViz(sameValueType(value.first(), it), "状态数组元素类型必须一致"); validateState(it, depth + 1) }
            }
            is JsonPrimitive -> when {
                value.isString -> requireViz(value.content.length <= VizLimits.STRING, "状态字符串过长")
                value != JsonNull && value.booleanOrNull == null -> value.num()
            }
        }
    }
}

private class SchemaValidator(
    val version: Int, val keys: Set<String>, val actions: Set<String>, val data: Set<String>,
    val derived: Set<String>, val register: (String) -> Unit,
) {
    private var nodes = 0
    private var shapes = 0
    private fun expression(value: JsonElement, locals: Set<String> = emptySet()) =
        validateExpression(value, keys, locals, data, derived)

    fun view(node: JsonObject, topLevel: Boolean) {
        requireViz(++nodes <= VizLimits.NODES, "view 超过 64 个节点")
        val type = node.string("type")
        val schema = VizComponentRegistry.nodes[type] ?: throw VizException("未知视图类型：$type")
        requireViz(version >= schema.since, "$type 需要 v3")
        node.fields(schema.required + "type", schema.optional + setOf("id", "visible") +
            if (version == 3 && topLevel) setOf("slot") else emptySet())
        node["id"]?.str()?.let(register)
        node["slot"]?.let { requireViz(it.str() in VizComponentRegistry.slots, "未知布局区域") }
        when (type) {
            "row", "column" -> node.value("children").arr().forEach { view(it.obj(), false) }
            "scene" -> {
                requireViz(node.number("width") in 32.0..2048.0 && node.number("height") in 32.0..2048.0, "scene 宽高必须在 32–2048 之间")
                val items = node.value("shapes").arr().map { it.obj() }
                val anchors = items.filter { it.string("type") == "labeledNode" }.mapNotNull { it["id"]?.str() }.toSet()
                items.forEach { shape(it, emptySet(), 0, anchors) }
            }
            "button" -> requireViz(node.string("action") in actions, "按钮动作不存在")
            "slider", "numberInput" -> {
                requireViz(node.string("bind") in keys, "控件绑定状态不存在")
                requireViz((node["min"]?.num() ?: -1e12) < (node["max"]?.num() ?: 1e12) &&
                    (node["step"]?.num() ?: 1.0) > 0, "控件范围或步长无效")
            }
            "select", "toggle", "stepper" -> {
                requireViz(node.string("bind") in keys, "控件绑定状态不存在")
                if (type == "select") {
                    val options = node.value("options").arr()
                    requireViz(options.size in 1..32, "选项数量必须在 1–32 之间")
                    options.forEach { option ->
                        option.obj().fields(setOf("value", "label"))
                        requireViz(option.obj().value("value") is JsonPrimitive && option.obj().value("value") != JsonNull, "选项值必须为非空标量")
                        option.obj().value("label").displayText()
                        requireViz(sameValueType(options.first().obj().value("value"), option.obj().value("value")), "选项值类型必须相同")
                    }
                    requireViz(options.map { it.obj().value("value") }.distinct().size == options.size, "选项值重复")
                }
                node["resetOn"]?.arr()?.forEach { requireViz(it.str() in keys && it.str() != node.string("bind"), "步骤复位参数无效") }
            }
            "table" -> {
                val columns = node.value("columns").arr()
                requireViz(columns.size in 1..8, "表格列数必须在 1–8 之间")
                columns.forEach { it.obj().fields(setOf("key", "label")); it.obj().string("key"); it.obj().value("label").displayText() }
                requireViz(columns.map { it.obj().string("key") }.distinct().size == columns.size, "表格列重复")
            }
        }
        node["tone"]?.let { requireViz(it.str() in setOf("neutral", "info", "success", "warning", "error"), "未知状态语气") }
        node["mode"]?.let { requireViz(it.str() in setOf("cells", "bars"), "未知序列模式") }
        node.filterKeys { it !in setOf("children", "shapes", "type", "id", "action", "bind", "slot", "options", "columns", "resetOn") }
            .values.forEach { expression(it) }
    }

    private fun shape(node: JsonObject, locals: Set<String>, repeatDepth: Int, anchors: Set<String>) {
        requireViz(++shapes <= VizLimits.SHAPES, "图元声明超过 512 项")
        val type = node.string("type")
        val schema = VizComponentRegistry.shapes[type] ?: throw VizException("未知图元类型：$type")
        requireViz(version >= schema.since, "$type 需要 v3")
        node.fields(schema.required + "type", schema.optional + setOf("id", "visible"))
        node["id"]?.str()?.let(register)
        if (type == "connector") {
            requireViz(repeatDepth == 0, "connector 不能位于 repeat 中")
            requireViz(node.string("from") in anchors && node.string("to") in anchors, "连接器端点必须是同一 scene 已声明的 labeledNode")
            listOf("fromAnchor", "toAnchor").forEach { field -> node[field]?.let {
                requireViz(it.str() in setOf("auto", "top", "right", "bottom", "left"), "锚点无效")
            } }
            node["route"]?.let { requireViz(it.str() in setOf("straight", "orthogonal"), "连线路由无效") }
            node["arrow"]?.let { requireViz(it.str() in setOf("none", "start", "end", "both"), "箭头方向无效") }
        }
        node.filterKeys { it !in setOf("type", "id", "shapes", "from", "to", "fromAnchor", "toAnchor", "route", "arrow") }.forEach { (key, value) ->
            expression(value, if (type == "plot" && key == "y") locals + "x" else locals)
        }
        if (type == "repeat") {
            requireViz(repeatDepth < 2, "repeat 嵌套超过两层")
            node.value("shapes").arr().forEach { shape(it.obj(), locals + setOf("item", "index"), repeatDepth + 1, emptySet()) }
        }
    }
}
