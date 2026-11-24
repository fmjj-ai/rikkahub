package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*

/** 解码、程序化文档和运行时共用边界；根值的深度为零。 */
object VizLimits {
    const val DOCUMENT_BYTES = 65_536
    const val RECORD_BYTES = 8_192
    const val RECORDS = 512
    const val JSON_DEPTH = 64
    const val EXPRESSION_DEPTH = 32
    const val STRING = 2048
    const val ARRAY = 256
    const val STATE = 32
    const val DATA = 16
    const val DERIVED = 32
    const val ACTIONS = 64
    const val NODES = 64
    const val SHAPES = 512
    const val SAMPLES = 2048
    const val OPERATIONS = 50_000
    const val DIAGNOSTICS = 16
    const val SEQUENCE_GAP = 32

    internal fun structure(value: JsonElement, depth: Int = 0): Int {
        requireViz(depth <= JSON_DEPTH, "JSON 嵌套超过 $JSON_DEPTH 层")
        val bytes = when (value) {
            is JsonObject -> 2 + value.entries.sumOf { (key, child) ->
                requireViz(key.length <= STRING, "字段名称过长")
                JsonPrimitive(key).toString().toByteArray(Charsets.UTF_8).size + 2 + structure(child, depth + 1)
            } - if (value.isEmpty()) 0 else 1
            is JsonArray -> {
                requireViz(value.size <= ARRAY, "数组超过 $ARRAY 项")
                2 + value.sumOf { structure(it, depth + 1) + 1 } - if (value.isEmpty()) 0 else 1
            }
            is JsonPrimitive -> {
                if (value.isString) requireViz(value.content.length <= STRING, "字符串超过 $STRING 字符")
                else if (value != JsonNull && value.booleanOrNull == null) value.num()
                value.toString().toByteArray(Charsets.UTF_8).size
            }
        }
        requireViz(bytes <= DOCUMENT_BYTES, "逻辑文档超过 64 KiB")
        return bytes
    }
}
