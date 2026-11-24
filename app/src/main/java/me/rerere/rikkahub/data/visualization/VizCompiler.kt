package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*

/** 每个会话独立的内容/依赖缓存；缓存命中仍支付整帧资源贡献。 */
class VizCompiler {
    private data class Entry(
        val definition: JsonElement,
        val inputs: List<Any?>,
        val result: Any?,
        val operations: Int,
        val shapes: Int,
        val samples: Int,
    )
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    internal fun <T> value(key: String, definition: JsonElement, evaluator: VizEvaluator, build: () -> T): T {
        val dependencies = evaluator.dependencies(definition).toMutableSet()
        fun bindings(value: JsonElement) {
            if (value is JsonObject) {
                (value["bind"] as? JsonPrimitive)?.let { dependencies += VizDependency("get", it.content) }
                value.values.forEach(::bindings)
            } else if (value is JsonArray) value.forEach(::bindings)
        }
        bindings(definition)
        dependencies.filter { it.kind == "data" }.forEach { dependency ->
            evaluator.data[dependency.key]?.inputKeys?.forEach { dependencies += VizDependency("get", it) }
        }
        val inputs = dependencies.sortedWith(compareBy({ it.kind }, { it.key })).map {
            when (it.kind) {
                "get" -> evaluator.state[it.key]
                "data" -> evaluator.data[it.key]
                else -> evaluator.derived[it.key]
            }
        }
        entries[key]?.takeIf { it.definition == definition && it.inputs == inputs }?.let {
            evaluator.budget.charge(it.operations, it.shapes, it.samples)
            @Suppress("UNCHECKED_CAST")
            return it.result as T
        }
        val budget = evaluator.budget
        val before = Triple(budget.operations, budget.shapes, budget.samples)
        val result = build()
        entries[key] = Entry(definition, inputs, result, budget.operations - before.first,
            budget.shapes - before.second, budget.samples - before.third)
        while (entries.size > 768) entries.entries.iterator().run { next(); remove() }
        return result
    }

    fun clear() = entries.clear()
}
