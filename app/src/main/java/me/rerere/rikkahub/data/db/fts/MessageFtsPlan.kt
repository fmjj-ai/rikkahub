package me.rerere.rikkahub.data.db.fts

import me.rerere.rikkahub.data.model.MessageNode

internal data class FtsMessageText(val messageId: String, val text: String)

internal data class FtsNodeMutation(val nodeId: String, val messages: List<FtsMessageText>)

internal fun MessageNode.ftsMessages(): List<FtsMessageText> = messages.mapNotNull { message ->
    val text = message.extractFtsText()
    if (text.isBlank()) null else FtsMessageText(message.id.toString(), text)
}

/** 只比较可搜索内容；选择、位置、推理和用量变化不重写索引。 */
internal fun planFtsNodeMutations(
    previous: List<MessageNode>,
    current: List<MessageNode>,
): List<FtsNodeMutation> {
    val previousById = previous.associateBy { it.id }
    val currentById = current.associateBy { it.id }
    return (previousById.keys + currentById.keys).mapNotNull { id ->
        val before = previousById[id]?.ftsMessages().orEmpty()
        val after = currentById[id]?.ftsMessages().orEmpty()
        if (before == after) null else FtsNodeMutation(id.toString(), after)
    }
}
