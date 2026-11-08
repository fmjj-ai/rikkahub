package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Assistant
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

/** 仅变换仍存在的目标助手，保留其它助手和运行时对象身份。 */
internal fun Settings.updateAssistant(id: Uuid, transform: (Assistant) -> Assistant): Settings {
    if (assistants.none { it.id == id }) return this
    return copy(assistants = assistants.map { assistant ->
        if (assistant.id != id) assistant else transform(assistant).also { require(it.id == id) }
    })
}

internal fun Settings.appearanceFileUrls(): Set<String> = buildSet {
    (displaySetting.userAvatar as? Avatar.Image)?.let { add(it.url) }
    assistants.forEach { assistant ->
        (assistant.avatar as? Avatar.Image)?.let { add(it.url) }
        assistant.background?.let { add(it) }
    }
}

internal fun Settings.cleanupAssistantTags(): Settings {
    val validIds = assistantTags.map { it.id }.toSet()
    val updated = assistants.map { assistant ->
        val tags = assistant.tags.filter { it in validIds }
        if (tags == assistant.tags) assistant else assistant.copy(tags = tags)
    }
    val used = updated.flatMap { it.tags }.toSet()
    return copy(assistants = updated, assistantTags = assistantTags.filter { it.id in used })
}

internal fun Settings.updateAssistantTags(
    assistantId: Uuid,
    transform: (List<Uuid>, List<Tag>) -> Pair<List<Uuid>, List<Tag>>,
): Settings {
    val assistant = assistants.find { it.id == assistantId } ?: return this
    val (selected, tags) = transform(assistant.tags, assistantTags)
    return copy(assistantTags = tags)
        .updateAssistant(assistantId) { it.copy(tags = selected.distinct()) }
        .cleanupAssistantTags()
}

/** 拖拽下标属于已过滤的展示列表，先映射稳定 ID，再移动最新的完整收藏集合。 */
internal fun Settings.reorderFavoriteModels(displayedIds: List<Uuid>, from: Int, to: Int): Settings = copy(
    favoriteModels = favoriteModels.moveById(displayedIds.getOrNull(from), displayedIds.getOrNull(to)) { it },
)

internal fun Settings.toggleFavoriteModel(modelId: Uuid): Settings = copy(
    favoriteModels = if (modelId in favoriteModels) favoriteModels.filterNot { it == modelId }
    else favoriteModels + modelId,
)
