package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.contextFingerprint
import org.junit.Assert.*
import org.junit.Test

class ConversationCheckpointTest {
    @Test
    fun `insertion retains original branches and newer messages`() {
        val first = UIMessage.user("问题")
        val answer = UIMessage.assistant("原回复")
        val alternative = UIMessage.assistant("另一个分支")
        val newer = UIMessage.user("压缩期间新增的消息")
        val nodes = listOf(first.toMessageNode(), MessageNode(messages = listOf(answer, alternative)), newer.toMessageNode())
        val conversation = Conversation.ofId(kotlin.uuid.Uuid.random(), messages = nodes)
        val point = UIMessage.user("摘要").copy(
            isContextCheckpoint = true,
            contextCheckpointHash = listOf(first, answer).contextFingerprint(),
        )

        val updated = conversation.insertContextCheckpoint(answer.id, point)!!

        assertEquals(nodes.take(2), updated.messageNodes.take(2))
        assertEquals(nodes.last(), updated.messageNodes.last())
        assertEquals(point, updated.messageNodes[2].currentMessage)
        val switched = conversation.copy(messageNodes = nodes.toMutableList().apply { this[1] = this[1].copy(selectIndex = 1) })
        assertNull(switched.insertContextCheckpoint(answer.id, point))
    }

    @Test
    fun `edited source refuses a stale summary even when message id stays the same`() {
        val first = UIMessage.user("原问题")
        val point = UIMessage.user("摘要").copy(isContextCheckpoint = true, contextCheckpointHash = listOf(first).contextFingerprint())
        val edited = first.copy(parts = UIMessage.user("已修改").parts)
        val conversation = Conversation.ofId(kotlin.uuid.Uuid.random(), messages = listOf(edited.toMessageNode()))
        assertNull(conversation.insertContextCheckpoint(first.id, point))
    }
}
