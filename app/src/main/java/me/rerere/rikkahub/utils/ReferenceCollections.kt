package me.rerere.rikkahub.utils

import java.util.IdentityHashMap

/** 一入一出的映射；没有对象变化时复用原列表，不计算元素的内容 hash。 */
internal inline fun <T : Any> List<T>.mapPreservingIdentity(transform: (T) -> T): List<T> {
    var updated: MutableList<T>? = null
    for (index in indices) {
        val current = this[index]
        val next = transform(current)
        if (next !== current) {
            if (updated == null) updated = toMutableList()
            updated[index] = next
        }
    }
    return updated ?: this
}

/** 单个流/订阅独占；只保留当前列表的对象投影，不累积过去的流式尾部快照。 */
internal class ReferenceProjectionCache<Input : Any, Output : Any>(
    private val projectItem: suspend (Input) -> Output,
) {
    private var cache = IdentityHashMap<Input, Output>()

    suspend fun project(items: List<Input>): List<Output> {
        val nextCache = IdentityHashMap<Input, Output>(items.size)
        val result = items.map { item ->
            val projected = nextCache[item] ?: cache[item] ?: projectItem(item)
            nextCache[item] = projected
            projected
        }
        cache = nextCache
        return result
    }
}
