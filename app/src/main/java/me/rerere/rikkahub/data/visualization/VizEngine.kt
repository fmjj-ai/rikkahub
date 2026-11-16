package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*
import kotlin.math.*

object VizEngine {
    fun frame(spec: VizSpec, state: JsonObject, cache: VizCompiler? = null, previous: VizFrame? = null): VizFrame {
        requireViz(spec.documentBytes - spec.initialStateBytes + VizLimits.structure(state) <= VizLimits.DOCUMENT_BYTES,
            "当前状态使逻辑文档超过 64 KiB")
        val evaluator = VizEvaluator(state, data = spec.data, derived = spec.derived, cache = cache)
        val compiler = FrameCompiler(spec, evaluator, previous)
        // 派生值默认态也要有效，即使尚无组件引用它。
        if (previous == null) spec.derived.keys.forEach {
            evaluator.eval(buildJsonObject { put("$", buildJsonArray { add("derived"); add(it) }) })
        }
        val nodes = if (spec.version < 3) spec.view.mapIndexedNotNull { i, source -> compiler.node(source, "view:$i") }
        else VizComponentRegistry.slots.flatMap { slot ->
            val scene = if (slot == "main") spec.scene?.let { compiler.node(it, "__scene") } else null
            listOfNotNull(scene) + spec.view.mapIndexedNotNull { i, source ->
                if ((source["slot"]?.str() ?: "controls") == slot) compiler.node(source, "view:$i") else null
            }
        }
        return VizFrame(state, nodes)
    }

    fun dispatch(spec: VizSpec, current: VizFrame, actionName: String, cache: VizCompiler? = null): VizFrame {
        val action = spec.actions[actionName] ?: throw VizException("动作不存在")
        requireViz(action.kind == "local", "消息动作必须由用户通过聊天入口发送")
        val evaluator = VizEvaluator(current.state, data = spec.data, derived = spec.derived, cache = cache)
        if (action.condition?.let { evaluator.eval(it).bool() } == false) return current
        // 所有右值读取同一旧快照；新状态及整帧通过验证后才交给宿主提交。
        val next = JsonObject(current.state + action.assignments.mapValues { (key, expression) ->
            evaluator.eval(expression).also {
                requireViz(sameValueType(spec.initialState.value(key), it), "动作改变了状态类型：$key")
                VizParser.validateState(it)
            }
        })
        return frame(spec, resetSteps(spec, current.state, next), cache, current)
    }

    fun slider(spec: VizSpec, current: VizFrame, node: VizNode, raw: Double): VizFrame {
        return input(spec, current, node, JsonPrimitive(raw))
    }

    fun input(spec: VizSpec, current: VizFrame, node: VizNode, raw: JsonElement, cache: VizCompiler? = null): VizFrame {
        val values = node.values
        val key = values.string("bind")
        requireViz(values["stale"]?.bool() != true, "输入已变化，需要重新生成演示")
        val value = when (node.type) {
            "slider", "numberInput" -> {
                val number = raw.num()
                val min = values.number("min"); val max = values.number("max")
                val step = values["step"]?.num() ?: 1.0
                if (node.type == "numberInput") requireViz(number in min..max, "请输入 $min 到 $max 之间的数值")
                JsonPrimitive(quantize(number, min, max, step))
            }
            "toggle" -> JsonPrimitive(raw.bool())
            "select" -> raw.also { candidate -> requireViz(values.value("options").arr().any {
                equalValues(it.obj().value("value"), candidate)
            }, "选项不存在") }
            "stepper" -> raw.also { requireViz(it.int() in 0 until values.value("count").int(), "步骤尚未接收") }
            else -> throw VizException("组件不接受输入")
        }
        requireViz(sameValueType(spec.initialState.value(key), value), "输入改变了状态类型")
        val next = resetSteps(spec, current.state, JsonObject(current.state + (key to value)))
        return frame(spec, next, cache, current)
    }

    internal fun quantize(raw: Double, min: Double, max: Double, step: Double): Double = when {
        raw <= min -> min
        raw >= max -> max
        else -> (min + floor((raw - min) / step + 0.5) * step).coerceIn(min, max)
    }

    private fun resetSteps(spec: VizSpec, old: JsonObject, next: JsonObject): JsonObject {
        val changed = next.keys.filter { !equalValues(old.value(it), next.value(it)) }.toSet()
        val resets = mutableMapOf<String, JsonElement>()
        fun visit(node: JsonObject) {
            if (node.string("type") == "stepper") {
                val bind = node.string("bind")
                val inputs = node["resetOn"]?.arr()?.map { it.str() } ?: spec.initialState.keys.filter { it != bind }
                if (inputs.any { it in changed } && bind !in changed) resets[bind] = JsonPrimitive(0)
            }
            node["children"]?.arr()?.forEach { visit(it.obj()) }
        }
        if (spec.version == 3) spec.view.forEach(::visit)
        return JsonObject(next + resets)
    }

    fun message(spec: VizSpec, current: VizFrame, actionName: String): String {
        val action = spec.actions[actionName] ?: throw VizException("动作不存在")
        requireViz(action.kind == "message", "需要消息动作")
        val evaluator = VizEvaluator(current.state, data = spec.data, derived = spec.derived)
        requireViz(action.condition?.let { evaluator.eval(it).bool() } != false, "当前条件不允许发送")
        val snapshot = JsonObject(action.include.associateWith { current.state.value(it) }).toString()
        requireViz(snapshot.toByteArray(Charsets.UTF_8).size <= VizLimits.RECORD_BYTES, "当前参数超过 8 KiB，请减少发送字段")
        return "${action.prompt}\n\n图卡：${spec.title}（${spec.id}）\n数据/当前参数：\n$snapshot"
    }
}

private class FrameCompiler(val spec: VizSpec, val evaluator: VizEvaluator, previous: VizFrame? = null) {
    private val budget get() = evaluator.budget
    private val lastNodes = buildMap<String, VizNode> {
        fun collect(node: VizNode) { node.values["id"]?.str()?.let { put(it, node) }; node.children.forEach(::collect) }
        previous?.nodes?.forEach(::collect)
    }
    private val recoverDisplayErrors = previous != null
    private val previousScenes = lastNodes.filterValues { it.type == "scene" }

    fun node(source: JsonObject, path: String): VizNode? {
        val identity = source["id"]?.str() ?: path
        val conditions = mutableListOf<JsonElement>()
        fun actions(node: JsonObject) {
            node["action"]?.str()?.let { spec.actions[it]?.condition }?.let { conditions += it }
            node["children"]?.arr()?.forEach { actions(it.obj()) }
        }
        actions(source)
        val definition = if (conditions.isEmpty()) source else JsonObject(source + ("__conditions" to JsonArray(conditions)))
        return try {
            if (evaluator.cache == null) compileNode(source, path)
            else evaluator.cache.value("node:$identity", definition, evaluator) { compileNode(source, path) }
        } catch (failure: VizException) {
            if (failure is VizBudgetException || !recoverDisplayErrors || source.string("type") !in setOf("text", "status")) throw failure
            lastNodes[identity]?.copy(error = failure.message) ?: VizNode("text", buildJsonObject {
                put("type", "text"); put("id", identity); put("text", "此处内容暂不可用")
            }, error = failure.message)
        }
    }

    private fun compileNode(source: JsonObject, path: String): VizNode? {
        if (source["visible"]?.let { evaluator.eval(it).bool() } == false) return null
        val type = source.string("type")
        val stale = evaluator.stale(source)
        var values = resolve(source, emptyMap(), setOf("children", "shapes", "visible"))
        values = JsonObject(values + ("id" to (source["id"] ?: JsonPrimitive(path))))
        val children = if (type in setOf("row", "column")) source.value("children").arr().mapIndexedNotNull { i, child -> node(child.obj(), "$path:$i") } else emptyList()
        val shapes = if (type == "scene") {
            val definitions = source.value("shapes").arr().map { it.obj() }
            val anchors = definitions.filter { it.string("type") == "labeledNode" }.associateBy { it["id"]?.str().orEmpty() }
            definitions.flatMapIndexed { index, item ->
                val key = "shape:$path:${item["id"]?.str() ?: index}"
                val definition = if (item.string("type") == "connector") JsonObject(item + mapOf(
                    "__from" to anchors.getValue(item.string("from")), "__to" to anchors.getValue(item.string("to")))) else item
                val sourceId = item["id"]?.str() ?: "shape:$index"
                try {
                    evaluator.cache?.value(key, definition, evaluator) {
                        shape(item, emptyMap(), anchors).map { it.copy(sourceId = sourceId) }
                    } ?: shape(item, emptyMap(), anchors).map { it.copy(sourceId = sourceId) }
                } catch (failure: VizException) {
                    if (failure is VizBudgetException || !recoverDisplayErrors ||
                        item.string("type") !in setOf("text", "labeledNode")) throw failure
                    val old = previousScenes[source["id"]?.str() ?: path]?.shapes?.filter { it.sourceId == sourceId }.orEmpty()
                    budget.charge(0, old.size, 0)
                    old.map { it.copy(error = failure.message) }
                }
            }
        } else emptyList()
        if (type == "scene" && spec.version == 3 && shapes.isEmpty()) return null
        listOf("text", "label").forEach { field -> values[field]?.let {
            values = JsonObject(values + (field to JsonPrimitive(it.displayText())))
        } }
        when (type) {
            "button" -> {
                val enabled = values["enabled"]?.bool() != false
                val allowed = spec.actions.getValue(values.string("action")).condition?.let {
                    evaluator.eval(it).bool()
                } != false
                values = JsonObject(values + ("enabled" to JsonPrimitive(enabled && allowed)))
            }
            "slider", "numberInput" -> {
                if (type == "numberInput") values = JsonObject(mapOf(
                    "min" to JsonPrimitive(-1e12), "max" to JsonPrimitive(1e12), "step" to JsonPrimitive(1),
                ) + values)
                val value = evaluator.state.value(values.string("bind")).num()
                requireViz(value in values.number("min")..values.number("max"), "数值状态超出范围")
                if (type == "slider") {
                    requireViz(values.number("min").toFloat() < values.number("max").toFloat(), "滑块范围小于界面精度")
                    requireViz((values.number("max") - values.number("min")) / values.number("step") <= 1e9, "控件步长过小")
                }
            }
            "toggle" -> evaluator.state.value(values.string("bind")).bool()
            "select" -> requireViz(values.value("options").arr().any {
                equalValues(it.obj().value("value"), evaluator.state.value(values.string("bind")))
            }, "当前值不在选项中")
            "stepper" -> {
                val count = values.value("count").int()
                val index = evaluator.state.value(values.string("bind")).int()
                requireViz(count in 0..256 && (index in 0 until count || count == 0 && index == 0), "步骤游标超出已接收范围")
            }
            "sequence" -> {
                val items = values.value("items").arr()
                requireViz(items.size <= 256, "序列超过 256 项")
                items.forEach { it.num() }
                values["roles"]?.arr()?.let { roles ->
                    requireViz(roles.size == items.size && roles.all { it.str() in setOf("normal", "current", "pivot", "compare", "sorted") }, "序列角色无效或长度不匹配")
                }
            }
            "table" -> {
                val rows = values.value("rows").arr()
                requireViz(rows.size <= 64, "表格超过 64 行")
                rows.forEach { row -> values.value("columns").arr().forEach { column ->
                    (row.obj()[column.obj().string("key")] ?: JsonNull).displayText()
                } }
            }
        }
        if (stale) values = JsonObject(values + ("stale" to JsonPrimitive(true)))
        return VizNode(type, values, children, shapes, error = shapes.firstNotNullOfOrNull { it.error })
    }

    private fun resolve(source: JsonObject, locals: Map<String, JsonElement>, excluded: Set<String> = emptySet()): JsonObject =
        JsonObject(source.filterKeys { it !in excluded }.mapValues { evaluator.eval(it.value, locals) })

    private fun coordinate(n: Double): Double = n.also { requireViz(abs(it) <= 100_000, "图元坐标超过 ±100000") }

    private fun shape(source: JsonObject, locals: Map<String, JsonElement>, anchors: Map<String, JsonObject> = emptyMap()): List<VizShape> {
        budget.tick()
        if (source["visible"]?.let { evaluator.eval(it, locals).bool() } == false) return emptyList()
        val type = source.string("type")
        if (type == "repeat") {
            val items = evaluator.eval(source.value("items"), locals).arr()
            requireViz(items.size <= 64, "repeat 超过 64 次")
            return items.flatMapIndexed { index, item ->
                source.value("shapes").arr().flatMap { shape(it.obj(), locals + mapOf("item" to item, "index" to JsonPrimitive(index))) }
            }
        }
        budget.charge(0, 1, 0)
        var values = resolve(source, locals, if (type == "plot") setOf("y", "visible") else setOf("visible"))
        if (type == "labeledNode" || type == "connector") {
            val endpoints = if (type == "connector") listOf(values.string("from"), values.string("to")) else emptyList()
            val expanded = VizPrimitives.expand(type, values, endpoints.associateWith { resolve(anchors.getValue(it), locals) })
            // 高层声明自身不计作绘图，但展开后的每个图元必须计数。
            budget.shapes--
            return expanded.flatMap { shape(it, locals) }
        }
        values["fill"]?.let { color(it.str()) }
        values["stroke"]?.let { color(it.str()) }
        values["sw"]?.let { requireViz(it.num() in 0.0..1000.0, "描边宽度无效") }
        values.filterKeys { it in setOf("x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "originX", "originY") }
            .values.forEach { coordinate(it.num()) }
        values.filterKeys { it in setOf("w", "h", "r") }.values.forEach {
            requireViz(it.num() in 0.0..100_000.0, "图元尺寸无效")
        }
        var paths: List<List<VizPoint>> = emptyList()
        var commands: List<VizPathCommand> = emptyList()
        when (type) {
            "text" -> {
                values = JsonObject(values + ("text" to JsonPrimitive(values.value("text").displayText())))
                values["size"]?.let { requireViz(it.num() in 1.0..256.0, "文字大小无效") }
                requireViz(values["anchor"]?.str() in listOf(null, "start", "middle", "end"), "文字对齐方式无效")
            }
            "polyline" -> paths = listOf(values.value("points").arr().map { point ->
                val pair = point.arr()
                requireViz(pair.size == 2, "折线坐标需要两个数字")
                VizPoint(coordinate(pair[0].num()), coordinate(pair[1].num()))
            })
            "path" -> commands = VizPath.parse(values.string("d"))
            "plot" -> {
                val samples = values.value("samples").int()
                requireViz(samples in 2..512, "每条曲线采样数必须为 2–512")
                budget.charge(0, 0, samples)
                val xMin = coordinate(values.number("xMin"))
                val xMax = coordinate(values.number("xMax"))
                requireViz(xMin < xMax, "曲线横轴范围无效")
                val scaleX = coordinate(values.number("scaleX"))
                val scaleY = coordinate(values.number("scaleY"))
                requireViz(scaleX > 0 && scaleY > 0, "曲线比例必须为正数")
                val segments = mutableListOf<MutableList<VizPoint>>()
                var segment: MutableList<VizPoint>? = null
                repeat(samples) { index ->
                    val x = xMin + (xMax - xMin) * index / (samples - 1)
                    val point = try {
                        val y = evaluator.eval(source.value("y"), locals + ("x" to JsonPrimitive(x))).num()
                        // 保留越界点让 Canvas 裁剪线段；限制浮点转换范围，避免绘制溢出。
                        val px = values.number("originX") + x * scaleX
                        val py = values.number("originY") - y * scaleY
                        if (abs(px) > 1e9 || abs(py) > 1e9) null else VizPoint(px, py)
                    } catch (_: VizMathException) { null }
                    if (point == null) segment = null else {
                        if (segment == null) { segment = mutableListOf(); segments += segment!! }
                        segment!!.add(point)
                    }
                }
                paths = segments
            }
        }
        return listOf(VizShape(type, values, paths, commands))
    }

    private fun color(value: String) {
        requireViz(value in setOf("none", "@primary", "@onSurface", "@surface", "@outline", "@success", "@warning") ||
            Regex("#[0-9a-fA-F]{6}").matches(value), "颜色必须使用主题 token 或 #RRGGBB")
    }
}

internal object VizPath {
    private val token = Regex("[MLHVZmlhvz]|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")

    fun parse(source: String): List<VizPathCommand> {
        val tokens = token.findAll(source).toList()
        var end = 0
        tokens.forEach {
            requireViz(source.substring(end, it.range.first).all { c -> c.isWhitespace() || c == ',' }, "path 包含不支持的指令")
            end = it.range.last + 1
        }
        requireViz(source.substring(end).all { it.isWhitespace() || it == ',' }, "path 包含无效字符")
        requireViz(tokens.size <= 768, "path 过长")
        var index = 0
        var command = ' '
        var x = 0.0; var y = 0.0; var startX = 0.0; var startY = 0.0
        val result = mutableListOf<VizPathCommand>()
        fun number(): Double {
            requireViz(index < tokens.size, "path 缺少坐标")
            val n = tokens[index++].value.toDoubleOrNull() ?: throw VizException("path 坐标无效")
            requireViz(n.isFinite() && abs(n) <= 100_000, "path 坐标超出范围")
            return n
        }
        while (index < tokens.size) {
            val next = tokens[index].value
            if (next.length == 1 && next[0].isLetter()) { command = next[0]; index++ }
            requireViz(result.isNotEmpty() || command.uppercaseChar() == 'M', "path 必须从 M 开始")
            requireViz(result.size < 256, "path 超过 256 段")
            val relative = command.isLowerCase()
            val kind = command.uppercaseChar()
            when (kind) {
                'M', 'L' -> {
                    x = number() + if (relative) x else 0.0
                    y = number() + if (relative) y else 0.0
                    if (kind == 'M') { startX = x; startY = y }
                }
                'H' -> x = number() + if (relative) x else 0.0
                'V' -> y = number() + if (relative) y else 0.0
                'Z' -> { x = startX; y = startY }
                else -> throw VizException("path 指令无效")
            }
            requireViz(abs(x) <= 100_000 && abs(y) <= 100_000, "path 累计坐标超出范围")
            result += VizPathCommand(if (kind in setOf('H', 'V')) 'L' else kind, x, y)
            command = when (command) { 'M' -> 'L'; 'm' -> 'l'; 'Z', 'z' -> ' '; else -> command }
        }
        return result
    }
}
