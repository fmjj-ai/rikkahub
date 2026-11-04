package me.rerere.ai.ui

import me.rerere.ai.util.json
import org.junit.Assert.*
import org.junit.Test

class ContextCheckpointTest {
    private val history = listOf(
        UIMessage.user("第一轮"), UIMessage.assistant("第一轮回复"),
        UIMessage.user("第二轮"), UIMessage.assistant("第二轮回复"),
    )

    private fun checkpoint(prefix: List<UIMessage>, text: String = "摘要") = UIMessage.user(text).copy(
        isContextCheckpoint = true,
        contextCheckpointHash = prefix.contextFingerprint(),
    )

    @Test
    fun `checkpoint preserves history and survives serialization`() {
        val prefix = history.take(2)
        val point = checkpoint(prefix)
        val messages = prefix + point + history.drop(2)

        assertEquals(history, messages.filterNot { it.isContextCheckpoint })
        assertEquals(listOf(point) + history.drop(2), messages.contextMessages())
        val restored = json.decodeFromString<List<UIMessage>>(json.encodeToString(messages))
        assertEquals(messages.contextMessages(), restored.contextMessages())
    }

    @Test
    fun `editing or switching earlier history invalidates the checkpoint`() {
        val messages = history.take(2) + checkpoint(history.take(2)) + history.drop(2)
        val edited = messages.toMutableList()
        edited[0] = UIMessage.user("修改后的分支")

        assertEquals(edited.filterNot { it.isContextCheckpoint }, edited.contextMessages())
    }

    @Test
    fun `message count limit never discards the active checkpoint`() {
        val point = checkpoint(history.take(2))
        val messages = (history.take(2) + point + history.drop(2)).contextMessages().limitContext(1)
        assertEquals(point, messages.first())
        assertEquals(history.last(), messages.last())
    }

    @Test
    fun `zero retained messages compresses all active history but not an isolated summary`() {
        assertEquals(history.lastIndex, history.compressionTarget(0))
        assertEquals(1, history.compressionTarget(1))
        val all = history + checkpoint(history)
        assertNull(all.compressionTarget(0))
        assertEquals(history.lastIndex + 2, (all + UIMessage.user("新的问题")).compressionTarget(0))
    }

    @Test
    fun `a later checkpoint reuses the earlier summary and invalidates with its source`() {
        val first = history.take(2) + checkpoint(history.take(2)) + history.drop(2)
        val second = first + checkpoint(first, "第二次摘要")
        assertEquals("第二次摘要", second.contextMessages().single().toText())

        val edited = second.toMutableList()
        edited[0] = UIMessage.user("源历史已经变化")
        assertEquals(history.size, edited.contextMessages().size)
    }

    @Test
    fun `quick compression does not compress only an old checkpoint again`() {
        val messages = history + checkpoint(history) + UIMessage.user("新问题") + UIMessage.assistant("新回复")
        assertNull(messages.compressionTarget(4))
        assertNull(messages.compressionTarget(1))
        assertEquals(messages.lastIndex, messages.compressionTarget(0))
        val continued = messages + UIMessage.user("再来一轮")
        assertEquals(messages.lastIndex, continued.compressionTarget(1))
    }

    @Test
    fun `checkpoint without a source fingerprint is not trusted`() {
        val point = UIMessage.user("无来源摘要").copy(isContextCheckpoint = true)
        assertEquals(history, (history + point).contextMessages())
    }
}
