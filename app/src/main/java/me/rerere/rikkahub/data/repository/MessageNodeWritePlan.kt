package me.rerere.rikkahub.data.repository

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

internal data class MessageNodePositionUpdate(
    val id: String,
    val nodeIndex: Int,
    val selectIndex: Int,
)

internal data class MessageNodeWritePlan(
    val inserts: List<MessageNodeEntity>,
    val updates: List<MessageNodeEntity>,
    val positions: List<MessageNodePositionUpdate>,
    val deletedIds: List<String>,
    val previousFtsNodes: List<MessageNode>,
    val currentFtsNodes: List<MessageNode>,
)

/** 以事务内读到的记录为准，不把运行态或容错加载结果当成已保存基线。 */
internal fun planMessageNodeWrites(
    conversationId: String,
    stored: List<MessageNodeEntity>,
    current: List<MessageNode>,
): MessageNodeWritePlan {
    require(stored.all { it.conversationId == conversationId })
    require(current.map { it.id }.toSet().size == current.size) { "会话内的消息节点 ID 必须唯一" }
    val remaining = stored.associateByTo(linkedMapOf()) { it.id }
    val inserts = mutableListOf<MessageNodeEntity>()
    val updates = mutableListOf<MessageNodeEntity>()
    val positions = mutableListOf<MessageNodePositionUpdate>()
    val deletedIds = mutableListOf<String>()
    val previousFtsNodes = mutableListOf<MessageNode>()
    val currentFtsNodes = mutableListOf<MessageNode>()

    current.forEachIndexed { index, node ->
        require(node.messages.none { it.hasBase64Part() })
        val id = node.id.toString()
        val previous = remaining.remove(id)
        val messages = JsonInstant.encodeToString(node.messages)
        if (previous == null || previous.messages != messages) {
            val entity = MessageNodeEntity(id, conversationId, index, messages, node.selectIndex)
            if (previous == null) {
                inserts.add(entity)
            } else {
                updates.add(entity)
                previousFtsNodes.add(previous.toMessageNode())
            }
            currentFtsNodes.add(node)
        } else if (previous.nodeIndex != index || previous.selectIndex != node.selectIndex) {
            positions.add(MessageNodePositionUpdate(id, index, node.selectIndex))
        }
    }

    remaining.values.forEach { entity ->
        val previous = entity.toMessageNode()
        // 展示层会过滤空节点，缺席不代表用户要求删除这类原始记录。
        if (previous.messages.isNotEmpty()) {
            deletedIds.add(entity.id)
            previousFtsNodes.add(previous)
        }
    }
    return MessageNodeWritePlan(inserts, updates, positions, deletedIds, previousFtsNodes, currentFtsNodes)
}

private fun MessageNodeEntity.toMessageNode(): MessageNode = MessageNode(
    id = Uuid.parse(id),
    messages = JsonInstant.decodeFromString<List<UIMessage>>(messages),
    selectIndex = selectIndex,
)
