package me.rerere.rikkahub.data.datastore

import kotlin.uuid.Uuid

/** 按稳定 ID 移动最新列表中的元素；过期拖动不会复活已删除项或丢掉新增项。 */
internal fun <T, K> List<T>.moveById(fromId: K?, toId: K?, id: (T) -> K): List<T> {
    if (fromId == null || toId == null || fromId == toId) return this
    val from = indexOfFirst { id(it) == fromId }
    val to = indexOfFirst { id(it) == toId }
    if (from < 0 || to < 0) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** 搜索服务保存的是下标；增删、排序时保持当前选中的服务身份。 */
internal fun Settings.updateSearchServices(
    transform: (List<me.rerere.search.SearchServiceOptions>) -> List<me.rerere.search.SearchServiceOptions>,
): Settings {
    val selectedId = searchServices.getOrNull(searchServiceSelected)?.id
    val updated = transform(searchServices)
    val selected = updated.indexOfFirst { it.id == selectedId }
        .takeIf { it >= 0 } ?: searchServiceSelected.coerceIn(0, (updated.size - 1).coerceAtLeast(0))
    return copy(searchServices = updated, searchServiceSelected = selected)
}

internal fun Settings.removeSearchService(id: Uuid): Settings = updateSearchServices { services ->
    // 保留原界面至少一个服务的约束，并在最新状态上检查，防止两个删除同时清空列表。
    if (services.size <= 1) services else services.filterNot { it.id == id }
}
