package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.contextFingerprint
import kotlin.uuid.Uuid

/** 摘要独立插入时间线，不按索引覆盖已有消息节点或分支。 */
internal fun Conversation.insertContextCheckpoint(
    afterMessageId: Uuid,
    checkpoint: UIMessage,
): Conversation? {
    require(checkpoint.isContextCheckpoint)
    val index = messageNodes.indexOfFirst { it.currentMessage.id == afterMessageId }
    if (index < 0) return null
    val prefix = currentMessages.take(index + 1)
    if (checkpoint.contextCheckpointHash != prefix.contextFingerprint()) return null
    return copy(
        messageNodes = messageNodes.take(index + 1) + checkpoint.toMessageNode() + messageNodes.drop(index + 1),
        chatSuggestions = emptyList(),
    )
}
