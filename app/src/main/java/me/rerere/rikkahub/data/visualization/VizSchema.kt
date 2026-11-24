package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*

/** 协议只描述值、事件和图元，不持有平台对象或可执行脚本。 */
data class VizSpec(
    val id: String,
    val title: String,
    val initialState: JsonObject,
    val actions: Map<String, VizAction>,
    val view: List<JsonObject>,
    val version: Int = 1,
    val scene: JsonObject? = null,
    val data: Map<String, VizDataset> = emptyMap(),
    val derived: JsonObject = JsonObject(emptyMap()),
    val documentBytes: Int = 0,
    val initialStateBytes: Int = 0,
)

data class VizDataset(
    val value: JsonElement,
    val inputKeys: List<String> = emptyList(),
    val inputSnapshot: JsonObject = JsonObject(emptyMap()),
)
data class VizAction(
    val condition: JsonElement?,
    val assignments: JsonObject = JsonObject(emptyMap()),
    val kind: String = "local",
    val prompt: String = "",
    val include: List<String> = emptyList(),
)
data class VizMessageEvent(
    val eventId: String,
    val conversationId: String,
    val messageId: String,
    val partIndex: Int,
    val ordinal: Int,
    val sourceDigest: String,
    val text: String,
)
data class VizPoint(val x: Double, val y: Double)
data class VizPathCommand(val command: Char, val x: Double = 0.0, val y: Double = 0.0)
data class VizShape(
    val type: String,
    val values: JsonObject,
    val paths: List<List<VizPoint>> = emptyList(),
    val commands: List<VizPathCommand> = emptyList(),
    val sourceId: String = "",
    val error: String? = null,
)
data class VizNode(
    val type: String,
    val values: JsonObject,
    val children: List<VizNode> = emptyList(),
    val shapes: List<VizShape> = emptyList(),
    val error: String? = null,
)
data class VizFrame(val state: JsonObject, val nodes: List<VizNode>)
data class VizFence(val ordinal: Int, val language: String, val code: String, val closed: Boolean)
data class VizSourceFence(val start: Int, val end: Int, val fence: VizFence)

data class VizStreamDocument(
    val specJson: JsonObject,
    val spec: VizSpec,
    val nextSequence: Int,
    val done: Boolean,
)

data class VizStreamState(
    val source: String = "",
    val pending: String = "",
    val document: VizStreamDocument? = null,
    val records: Int = 0,
    val error: String? = null,
    val failed: Boolean = false,
    val physicalLines: Int = 0,
    val ledger: Map<Int, String> = emptyMap(),
    val diagnostics: List<VizDiagnostic> = emptyList(),
    val diagnosticCount: Int = 0,
    val lifecycle: VizLifecycle = VizLifecycle.RECEIVING,
)

open class VizException(message: String) : IllegalArgumentException(message)
class VizMathException(message: String) : VizException(message)
class VizBudgetException(message: String) : VizException(message)

internal fun requireViz(value: Boolean, message: String) {
    if (!value) throw VizException(message)
}

internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw VizException("需要 JSON 对象")
internal fun JsonElement.arr(): JsonArray = this as? JsonArray ?: throw VizException("需要数组")
internal fun JsonElement.str(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw VizException("需要字符串")
internal fun JsonElement.bool(): Boolean = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    ?: throw VizException("需要布尔值")
internal fun JsonElement.num(): Double {
    val number = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        ?: throw VizException("需要数字")
    if (!number.isFinite() || kotlin.math.abs(number) > 1e12) throw VizMathException("数字超出有限范围")
    return number
}
internal fun JsonElement.int(): Int {
    val n = num()
    requireViz(n % 1.0 == 0.0 && n in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble(), "需要范围内的整数")
    return n.toInt()
}
internal fun JsonObject.value(name: String): JsonElement = this[name] ?: throw VizException("缺少字段：$name")
internal fun JsonObject.string(name: String): String = value(name).str()
internal fun JsonObject.number(name: String): Double = value(name).num()
internal fun JsonObject.fields(required: Set<String>, optional: Set<String> = emptySet()) {
    requireViz(keys.containsAll(required), "缺少字段：${required - keys}")
    requireViz((keys - required - optional).isEmpty(), "未知字段：${keys - required - optional}")
}

/** 在构建递归对象前限制深度，并检测解码后相同的 JSON 键。 */
internal class VizJsonReader(private val source: String) {
    private var offset = 0

    fun read(): JsonElement {
        requireViz(source.length <= 65536 && source.toByteArray(Charsets.UTF_8).size <= 65536, "图块超过 64 KiB")
        val result = value(0)
        whitespace()
        requireViz(offset == source.length, "JSON 末尾有多余内容")
        return result
    }

    private fun whitespace() {
        while (offset < source.length && source[offset] in " \r\n\t") offset++
    }

    private fun consume(c: Char): Boolean {
        whitespace()
        return if (source.getOrNull(offset) == c) { offset++; true } else false
    }

    private fun value(depth: Int): JsonElement {
        requireViz(depth <= VizLimits.JSON_DEPTH, "JSON 嵌套超过 ${VizLimits.JSON_DEPTH} 层")
        whitespace()
        val current = source.getOrNull(offset) ?: throw VizException("JSON 内容为空或已截断")
        return when (current) {
            '{' -> {
                offset++
                val values = linkedMapOf<String, JsonElement>()
                if (!consume('}')) {
                    do {
                        whitespace()
                        val key = string()
                        requireViz(key !in values, "JSON 键重复：$key")
                        requireViz(consume(':'), "JSON 缺少冒号")
                        values[key] = value(depth + 1)
                    } while (consume(','))
                    requireViz(consume('}'), "JSON 对象未闭合")
                }
                JsonObject(values)
            }
            '[' -> {
                offset++
                val values = mutableListOf<JsonElement>()
                if (!consume(']')) {
                    do {
                        requireViz(values.size < 256, "数组超过 256 项")
                        values += value(depth + 1)
                    } while (consume(','))
                    requireViz(consume(']'), "JSON 数组未闭合")
                }
                JsonArray(values)
            }
            '"' -> JsonPrimitive(string())
            else -> {
                val start = offset
                while (offset < source.length && source[offset] !in " \r\n\t,]}") offset++
                val token = source.substring(start, offset)
                when (token) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    "null" -> JsonNull
                    else -> {
                        requireViz(NUMBER.matches(token), "JSON 数字或字面量无效")
                        JsonPrimitive(token.toDoubleOrNull() ?: throw VizException("数字无效")).also { it.num() }
                    }
                }
            }
        }
    }

    private fun string(): String {
        requireViz(source.getOrNull(offset) == '"', "JSON 字符串必须使用双引号")
        val start = offset++
        while (offset < source.length) {
            requireViz(source[offset].code >= 0x20, "JSON 字符串包含未转义控制字符")
            when (source[offset++]) {
                '\\' -> {
                    requireViz(offset < source.length, "JSON 转义未完成")
                    offset++
                }
                '"' -> {
                    val decoded = try {
                        Json.decodeFromString<String>(source.substring(start, offset))
                    } catch (_: Exception) { throw VizException("JSON 字符串转义无效") }
                    requireViz(decoded.length <= 2048, "字符串超过 2048 字符")
                    return decoded
                }
            }
        }
        throw VizException("JSON 字符串未闭合")
    }

    companion object {
        private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    }
}
