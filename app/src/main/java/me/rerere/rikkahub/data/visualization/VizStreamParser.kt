package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*

/** 完整行是事务边界；错误只消费序号，已经发布的文档不回退。 */
object VizStreamParser {
    const val MAX_BYTES = VizLimits.DOCUMENT_BYTES
    const val MAX_RECORD_BYTES = VizLimits.RECORD_BYTES
    const val MAX_RECORDS = VizLimits.RECORDS

    fun advance(
        previous: VizStreamState,
        fragment: String,
        onDiagnostic: ((VizDiagnostic) -> Unit)? = null,
        onAcceptedSpec: ((VizSpec) -> Unit)? = null,
    ): VizStreamState {
        if (previous.failed || fragment.isEmpty()) return previous
        val source = previous.source + fragment
        if (source.length > MAX_BYTES || source.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return diagnose(
                previous,
                "stream_budget",
                "流式图块超过 64 KiB",
                onDiagnostic = onDiagnostic,
                fatal = true,
            )
        }
        val input = previous.pending + fragment
        var state = previous.copy(source = source, pending = "")
        var cursor = 0
        while (true) {
            val end = input.indexOf('\n', cursor)
            if (end < 0) break
            state = acceptLine(state, input.substring(cursor, end).removeSuffix("\r"), onAcceptedSpec, onDiagnostic)
            if (state.failed) return state
            cursor = end + 1
        }
        val pending = input.substring(cursor)
        return if (pending.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
            diagnose(state, "record_budget", "单条流式记录超过 8 KiB", onDiagnostic = onDiagnostic, fatal = true)
        } else state.copy(pending = pending)
    }

    /** 只有真实闭合才刷新尾行；取消不尝试修补半条 JSON。 */
    fun finish(
        previous: VizStreamState,
        onDiagnostic: ((VizDiagnostic) -> Unit)? = null,
        onAcceptedSpec: ((VizSpec) -> Unit)? = null,
    ): VizStreamState {
        if (previous.failed) return previous
        val state = if (previous.pending.isBlank()) previous.copy(pending = "") else {
            acceptLine(previous.copy(pending = ""), previous.pending.removeSuffix("\r"), onAcceptedSpec, onDiagnostic)
        }
        return when {
            state.failed -> state
            state.document?.done == true -> state.copy(lifecycle = VizLifecycle.COMPLETE)
            else -> state.copy(error = state.error ?: "可视化未收到 done 记录，已保留当前画面", lifecycle = VizLifecycle.INCOMPLETE)
        }
    }

    private fun diagnose(
        state: VizStreamState,
        code: String,
        message: String,
        operation: JsonObject? = null,
        onDiagnostic: ((VizDiagnostic) -> Unit)? = null,
        fatal: Boolean = false,
    ): VizStreamState {
        val diagnostic = VizDiagnostic(code, message, physicalLine = state.physicalLines,
            seq = runCatching { operation?.get("seq")?.int() }.getOrNull(),
            op = (operation?.get("op") as? JsonPrimitive)?.content,
            itemId = (operation?.get("id") as? JsonPrimitive)?.content,
            fieldPath = if (operation?.containsKey("data") == true) "data" else null)
        onDiagnostic?.invoke(diagnostic)
        return state.copy(error = diagnostic.summary(), failed = fatal,
            diagnostics = (state.diagnostics + diagnostic).takeLast(VizLimits.DIAGNOSTICS),
            diagnosticCount = state.diagnosticCount + 1,
            lifecycle = if (fatal) VizLifecycle.FAILED else state.lifecycle)
    }

    private fun acceptLine(
        previous: VizStreamState,
        line: String,
        onAcceptedSpec: ((VizSpec) -> Unit)?,
        onDiagnostic: ((VizDiagnostic) -> Unit)?,
    ): VizStreamState {
        var state = previous.copy(physicalLines = previous.physicalLines + 1)
        if (line.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
            return diagnose(state, "record_budget", "单条流式记录超过 8 KiB", onDiagnostic = onDiagnostic, fatal = true)
        }
        if (line.isBlank()) return state
        state = state.copy(records = state.records + 1)
        if (state.records > MAX_RECORDS) {
            return diagnose(state, "record_budget", "流式图块超过 $MAX_RECORDS 条记录", onDiagnostic = onDiagnostic, fatal = true)
        }
        val operation = try { VizJsonReader(line).read().obj() } catch (error: IllegalArgumentException) {
            return diagnose(state, "json", error.message ?: "JSON 无效", onDiagnostic = onDiagnostic)
        }
        val op = runCatching { operation.string("op") }.getOrNull()
        if (op == "init") {
            if (state.document != null) return diagnose(state, "init", "init 只能出现一次", operation, onDiagnostic)
            val version = runCatching { operation.value("version").int() }.getOrNull()
            if (version != null && version !in setOf(2, 3)) {
                return diagnose(state, "version", "未知可视化协议版本", operation, onDiagnostic, true)
            }
        } else {
            val document = state.document ?: return diagnose(state, "init", "记录必须在合法 init 之后", operation, onDiagnostic)
            val seq = runCatching { operation.value("seq").int() }.getOrNull()
                ?: return diagnose(state, "sequence", "记录序号无效", operation, onDiagnostic)
            val digest = VizFenceScanner.digest(line.trim())
            state.ledger[seq]?.let {
                return if (it == digest) state else {
                    diagnose(state, "conflict", "相同序号内容冲突，已保留原记录", operation, onDiagnostic)
                }
            }
            if (document.done) return diagnose(state, "done", "done 之后不接受记录", operation, onDiagnostic)
            if (seq < document.nextSequence) return diagnose(state, "sequence", "序号已消费或倒退", operation, onDiagnostic)
            if (seq - document.nextSequence > VizLimits.SEQUENCE_GAP) {
                return diagnose(state, "sequence", "序号缺口超过 32，需重新生成图卡", operation, onDiagnostic, true)
            }
            if (seq > document.nextSequence) {
                state = diagnose(state, "gap", "缺少序号 ${document.nextSequence}–${seq - 1}", operation, onDiagnostic)
            }
            state = state.copy(document = document.copy(nextSequence = seq + 1), ledger = state.ledger + (seq to digest))
        }
        return try {
            val candidate = apply(state.document, operation)
            if (candidate.spec !== state.document?.spec) {
                if (onAcceptedSpec != null) onAcceptedSpec(candidate.spec)
                else VizEngine.frame(candidate.spec, candidate.spec.initialState)
            }
            state.copy(document = candidate, lifecycle = if (candidate.done) VizLifecycle.COMPLETE else VizLifecycle.RECEIVING)
        } catch (error: IllegalArgumentException) {
            diagnose(state, "record", error.message ?: "流式记录无效", operation, onDiagnostic)
        }
    }

    private fun apply(current: VizStreamDocument?, operation: JsonObject): VizStreamDocument {
        val op = operation.string("op")
        if (op == "init") {
            val version = operation.value("version").int()
            requireViz(version in setOf(2, 3), "流式协议版本必须为 2 或 3")
            operation.fields(setOf("op", "version", "id") + if (version == 2) setOf("width", "height") else emptySet(),
                setOf("title", "state", "actions") + if (version == 3) setOf("width", "height") else emptySet())
            requireViz(operation.containsKey("width") == operation.containsKey("height"), "宽高必须成对提供")
            val scene = buildJsonObject {
                put("type", "scene"); put("width", operation["width"] ?: JsonPrimitive(340))
                put("height", operation["height"] ?: JsonPrimitive(220)); put("shapes", JsonArray(emptyList()))
            }
            val root = buildJsonObject {
                put("version", if (version == 2) 1 else 3)
                put("id", operation.value("id")); put("title", operation["title"] ?: operation.value("id"))
                put("state", operation["state"] ?: JsonObject(emptyMap()))
                put("actions", operation["actions"] ?: JsonObject(emptyMap()))
                put("view", JsonArray(if (version == 2) listOf(scene) else emptyList()))
                if (version == 3) { put("scene", scene); put("data", JsonObject(emptyMap())); put("derived", JsonObject(emptyMap())) }
            }
            return VizStreamDocument(root, VizParser.parseElement(root), 1, false)
        }
        val document = current ?: throw VizException("缺少 init")
        if (op == "done") {
            operation.fields(setOf("op", "seq"))
            return document.copy(done = true)
        }
        val old = document.specJson
        val v3 = document.spec.version == 3
        val root = when (op) {
            "shape", "replace", "remove" -> updateShape(old, operation, op, v3)
            "node", "replaceNode", "removeNode" -> updateNode(old, operation, op, v3)
            "state", "action", "derived", "data" -> {
                val stateOp = op == "state"
                requireViz(v3 || op in setOf("state", "action"), "$op 需要 v3")
                val keyField = if (stateOp) "key" else "id"
                val valueField = if (op == "action") "data" else "value"
                operation.fields(setOf("op", "seq", keyField, valueField), if (op == "data") setOf("inputKeys", "inputSnapshot") else emptySet())
                val key = operation.string(keyField)
                VizParser.validId(key, if (v3 && !stateOp) 3 else 1)
                val section = if (op == "action") "actions" else op
                val entries = old[section]?.obj() ?: JsonObject(emptyMap())
                requireViz(key !in entries, "$section 已声明：$key")
                val value = if (op == "data") JsonObject(operation.filterKeys { it in setOf("value", "inputKeys", "inputSnapshot") }) else operation.value(valueField)
                JsonObject(old + (section to JsonObject(entries + (key to value))))
            }
            "appendData" -> {
                requireViz(v3, "appendData 需要 v3")
                operation.fields(setOf("op", "seq", "id", "items"))
                val id = operation.string("id")
                val data = old.value("data").obj()
                val entry = data.value(id).obj()
                val items = entry.value("value").arr() + operation.value("items").arr()
                requireViz(items.size <= VizLimits.ARRAY, "数据数组超过 256 项")
                JsonObject(old + ("data" to JsonObject(data + (id to JsonObject(entry + ("value" to JsonArray(items)))))))
            }
            else -> throw VizException("未知流式操作：$op")
        }
        return document.copy(specJson = root, spec = VizParser.parseElement(root))
    }

    private fun updateShape(root: JsonObject, operation: JsonObject, op: String, v3: Boolean): JsonObject {
        operation.fields(if (op == "remove") setOf("op", "seq", "id") else setOf("op", "seq", "id", "data"))
        val id = operation.string("id").also { VizParser.validId(it, if (v3) 3 else 1) }
        val view = root.value("view").arr()
        val scene = if (v3) root.value("scene").obj() else view.first().obj()
        val shapes = scene.value("shapes").arr()
        if (op == "shape") requireViz(view.none { it.obj()["id"]?.str() == id }, "id 重复：$id")
        val updated = updateItems(shapes, operation, id, op == "shape", op == "remove")
        val nextScene = JsonObject(scene + ("shapes" to JsonArray(updated)))
        return if (v3) JsonObject(root + ("scene" to nextScene))
        else JsonObject(root + ("view" to JsonArray(listOf(nextScene) + view.drop(1))))
    }

    private fun updateNode(root: JsonObject, operation: JsonObject, op: String, v3: Boolean): JsonObject {
        operation.fields(if (op == "removeNode") setOf("op", "seq", "id") else setOf("op", "seq", "id", "data"),
            if (v3 && op != "removeNode") setOf("slot") else emptySet())
        val id = operation.string("id").also { VizParser.validId(it, if (v3) 3 else 1) }
        val view = root.value("view").arr()
        val nodes = if (v3) view else JsonArray(view.drop(1))
        if (!v3 && op == "node") requireViz(view.first().obj().value("shapes").arr().none { it.obj()["id"]?.str() == id }, "id 重复：$id")
        val updated = updateItems(nodes, operation, id, op == "node", op == "removeNode", v3)
        return JsonObject(root + ("view" to JsonArray(if (v3) updated else listOf(view.first()) + updated)))
    }

    private fun updateItems(items: JsonArray, operation: JsonObject, id: String, add: Boolean, remove: Boolean, slot: Boolean = false): List<JsonElement> {
        val index = items.indexOfFirst { it.obj()["id"]?.str() == id }
        requireViz(if (add) index < 0 else index >= 0, if (add) "id 重复：$id" else "待更新项目不存在：$id")
        if (remove) return items.filterIndexed { i, _ -> i != index }
        val data = operation.value("data").obj()
        requireViz(data["id"] == null && data["slot"] == null, "id/slot 使用记录字段")
        var value = JsonObject(data + ("id" to JsonPrimitive(id)))
        if (slot) value = JsonObject(value + ("slot" to (operation["slot"] ?: items.getOrNull(index)?.obj()?.get("slot") ?: JsonPrimitive("controls"))))
        return if (add) items + value else items.toMutableList().also { it[index] = value }
    }
}
