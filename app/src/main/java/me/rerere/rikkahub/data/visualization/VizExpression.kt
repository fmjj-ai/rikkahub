package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*
import java.util.Locale
import kotlin.math.*

internal class VizBudget {
    var operations = 0
    var shapes = 0
    var samples = 0
    fun tick() { charge(1, 0, 0) }
    fun charge(ops: Int, shapeCount: Int, sampleCount: Int) {
        operations += ops
        shapes += shapeCount
        samples += sampleCount
        if (operations > VizLimits.OPERATIONS) throw VizBudgetException("计算超过 50,000 步")
        if (shapes > VizLimits.SHAPES) throw VizBudgetException("展开图元超过 512 项")
        if (samples > VizLimits.SAMPLES) throw VizBudgetException("总采样超过 2048 点")
    }
}

internal val vizArities = mapOf(
    "get" to 1, "var" to 1, "add" to 2, "sub" to 2, "mul" to 2, "div" to 2, "pow" to 2,
    "min" to 2, "max" to 2, "neg" to 1, "abs" to 1, "sin" to 1, "cos" to 1, "sqrt" to 1,
    "eq" to 2, "lt" to 2, "lte" to 2, "gt" to 2, "gte" to 2, "and" to 2, "or" to 2,
    "not" to 1, "if" to 3, "fixed" to 2, "at" to 2, "len" to 1, "swap" to 3, "cat" to -1,
    "data" to 1, "derived" to 1, "field" to 2,
)

internal fun validateExpression(
    value: JsonElement, keys: Set<String>, locals: Set<String> = emptySet(),
    data: Set<String> = emptySet(), derived: Set<String> = emptySet(), depth: Int = 0,
) {
    fun visit(child: JsonElement, nextDepth: Int = depth) =
        validateExpression(child, keys, locals, data, derived, nextDepth)
    when (value) {
        is JsonArray -> value.forEach { visit(it) }
        is JsonObject -> {
            if (value.keys == setOf("$")) {
                val args = value.value("$").arr()
                requireViz(args.isNotEmpty(), "表达式缺少操作")
                val op = args[0].str()
                requireViz(depth < VizLimits.EXPRESSION_DEPTH, "表达式操作嵌套超过 32 层")
                val arity = vizArities[op] ?: throw VizException("不支持操作：$op")
                requireViz(if (arity < 0) args.size >= 3 else args.size == arity + 1, "操作 $op 参数数量错误")
                when (op) {
                    "get" -> requireViz(args[1].str() in keys, "读取了未声明的状态")
                    "var" -> requireViz(args[1].str() in locals, "局部变量不在当前作用域")
                    "data" -> requireViz(args[1].str() in data, "读取了未声明的数据集")
                    "derived" -> requireViz(args[1].str() in derived, "派生值必须先声明且不能循环引用")
                    "field" -> { args[2].str(); visit(args[1], depth + 1) }
                    else -> args.drop(1).forEach { visit(it, depth + 1) }
                }
            } else value.values.forEach { visit(it) }
        }
        else -> Unit
    }
}

internal class VizEvaluator(
    val state: JsonObject,
    val budget: VizBudget = VizBudget(),
    val data: Map<String, VizDataset> = emptyMap(),
    val derived: JsonObject = JsonObject(emptyMap()),
    val cache: VizCompiler? = null,
) {
    private val derivedValues = mutableMapOf<String, JsonElement>()
    private val evaluating = mutableSetOf<String>()

    fun dependencies(value: JsonElement): Set<VizDependency> = vizDependencies(value, derived)

    fun stale(value: JsonElement): Boolean = dependencies(value).any { dependency ->
        dependency.kind == "data" && data[dependency.key]?.let { dataset ->
            dataset.inputKeys.any { !equalValues(state.value(it), dataset.inputSnapshot.value(it)) }
        } == true
    }

    fun eval(value: JsonElement, locals: Map<String, JsonElement> = emptyMap()): JsonElement {
        budget.tick()
        if (value is JsonArray) return JsonArray(value.map { eval(it, locals) })
        if (value !is JsonObject) return value
        if (value.keys != setOf("$")) return JsonObject(value.mapValues { eval(it.value, locals) })
        val args = value.value("$").arr()
        val op = args[0].str()
        fun arg(i: Int) = eval(args[i], locals)
        fun n(i: Int) = arg(i).num()
        fun b(i: Int) = arg(i).bool()
        fun number(n: Double): JsonPrimitive = JsonPrimitive(n).also { it.num() }
        fun index(element: JsonElement, size: Int): Int = element.int().also {
            requireViz(it in 0 until size, "数组下标越界")
        }
        return when (op) {
            "get" -> state.value(args[1].str())
            "data" -> data[args[1].str()]?.value ?: throw VizException("数据集不存在")
            "derived" -> derivedValues.getOrPut(args[1].str()) {
                val id = args[1].str()
                requireViz(evaluating.add(id), "派生值循环引用")
                try {
                    val expression = derived.value(id)
                    cache?.value("derived:$id", expression, this) { eval(expression) } ?: eval(expression)
                } finally { evaluating.remove(id) }
            }
            "field" -> arg(1).obj().value(args[2].str())
            "var" -> locals[args[1].str()] ?: throw VizException("局部变量不可用")
            "if" -> if (b(1)) arg(2) else arg(3)
            "and" -> JsonPrimitive(b(1) && b(2))
            "or" -> JsonPrimitive(b(1) || b(2))
            "not" -> JsonPrimitive(!b(1))
            "add" -> number(n(1) + n(2))
            "sub" -> number(n(1) - n(2))
            "mul" -> number(n(1) * n(2))
            "div" -> { val a = n(1); val d = n(2); if (d == 0.0) throw VizMathException("除数为零"); number(a / d) }
            "pow" -> number(n(1).pow(n(2)))
            "min" -> number(min(n(1), n(2)))
            "max" -> number(max(n(1), n(2)))
            "neg" -> number(-n(1))
            "abs" -> number(abs(n(1)))
            "sin" -> number(sin(n(1)))
            "cos" -> number(cos(n(1)))
            "sqrt" -> number(sqrt(n(1)))
            "eq" -> JsonPrimitive(equalValues(arg(1), arg(2)))
            "lt" -> JsonPrimitive(n(1) < n(2))
            "lte" -> JsonPrimitive(n(1) <= n(2))
            "gt" -> JsonPrimitive(n(1) > n(2))
            "gte" -> JsonPrimitive(n(1) >= n(2))
            "fixed" -> {
                val valueToFormat = n(1)
                val decimals = arg(2).int()
                requireViz(decimals in 0..6, "小数位必须在 0–6 之间")
                JsonPrimitive(String.format(Locale.ROOT, "%.${decimals}f", valueToFormat))
            }
            "cat" -> JsonPrimitive(args.drop(1).joinToString("") {
                val item = eval(it, locals)
                requireViz(item is JsonPrimitive && item != JsonNull, "cat 只接受字符串、数字或布尔值")
                val primitive = item as JsonPrimitive
                if (!primitive.isString && primitive.booleanOrNull == null) {
                    val numeric = primitive.num()
                    if (numeric % 1.0 == 0.0) numeric.toLong().toString() else numeric.toString()
                } else primitive.content
            }.also { requireViz(it.length <= 2048, "拼接结果超过 2048 字符") })
            "at" -> { val array = arg(1).arr(); array[index(arg(2), array.size)] }
            "len" -> JsonPrimitive(arg(1).arr().size)
            "swap" -> {
                val array = arg(1).arr().toMutableList()
                val a = index(arg(2), array.size)
                val b = index(arg(3), array.size)
                val previous = array[a]; array[a] = array[b]; array[b] = previous
                JsonArray(array)
            }
            else -> throw VizException("不支持操作：$op")
        }
    }
}

/** 仅显示字段采用标量格式，结构字段继续严格读取字符串。 */
internal fun JsonElement.displayText(placeholder: String = "—"): String = when {
    this == JsonNull -> placeholder
    this !is JsonPrimitive -> throw VizException("需要文本标量，实际为${if (this is JsonArray) "数组" else "对象"}")
    isString || booleanOrNull != null -> content
    else -> {
        val value = num()
        if (value == 0.0) "0" else java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}

internal data class VizDependency(val kind: String, val key: String)

internal fun vizDependencies(value: JsonElement, derived: JsonObject = JsonObject(emptyMap())): Set<VizDependency> {
    val result = mutableSetOf<VizDependency>()
    fun visit(item: JsonElement) {
        if (item is JsonObject) {
            val args = (item["$"] as? JsonArray)
            val op = (args?.firstOrNull() as? JsonPrimitive)?.content
            if (op in setOf("get", "data", "derived")) {
                val dependency = VizDependency(op!!, args!![1].str())
                if (result.add(dependency) && op == "derived") derived[dependency.key]?.let(::visit)
            } else item.values.forEach(::visit)
        } else if (item is JsonArray) item.forEach(::visit)
    }
    visit(value)
    return result
}

internal fun equalValues(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString &&
        a.doubleOrNull != null && b.doubleOrNull != null -> a.num() == b.num()
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { equalValues(a[it], b[it]) }
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.all { equalValues(it.value, b.value(it.key)) }
    else -> a == b
}

internal fun sameValueType(old: JsonElement, new: JsonElement): Boolean = when (old) {
    is JsonArray -> new is JsonArray && new.all { old.isEmpty() || sameValueType(old.first(), it) } &&
        new.all { new.isEmpty() || sameValueType(new.first(), it) }
    is JsonObject -> new is JsonObject && old.keys == new.keys && old.all { sameValueType(it.value, new.value(it.key)) }
    is JsonPrimitive -> new is JsonPrimitive && when {
        old == JsonNull -> new == JsonNull
        old.isString -> new.isString
        old.booleanOrNull != null -> !new.isString && new.booleanOrNull != null
        else -> !new.isString && new.doubleOrNull != null
    }
}
