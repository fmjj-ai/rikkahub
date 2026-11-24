package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationStreamingTest {
    private fun message(text: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun longHistoryOnlyReplacesTheChangedTailNode() {
        val messages = List(2_000) { message("message $it") }
        val original = Conversation.ofId(Uuid.random(), Uuid.random())
            .copy(messageNodes = messages.map { it.toMessageNode() })
        val tail = messages.last().copy(parts = listOf(UIMessagePart.Text("changed tail")))
        val updated = original.updateCurrentMessages(messages.dropLast(1) + tail)
        for (index in 0 until original.messageNodes.lastIndex) {
            assertSame(original.messageNodes[index], updated.messageNodes[index])
        }
        assertEquals(original.messageNodes.last().id, updated.messageNodes.last().id)
        assertNotSame(original.messageNodes.last(), updated.messageNodes.last())
        assertSame(tail, updated.messageNodes.last().currentMessage)
    }

    @Test
    fun equalMessagesAndEmptyPrefixKeepConversationIdentity() {
        val first = message("first")
        val original = Conversation.ofId(Uuid.random(), Uuid.random()).updateCurrentMessages(listOf(first))
        assertSame(original, original.updateCurrentMessages(listOf(first)))
        assertSame(original, original.updateCurrentMessages(listOf(first.copy())))
        assertSame(original, original.updateCurrentMessages(emptyList()))
    }

    @Test
    fun prefixUpdateDoesNotTruncateExistingTail() {
        val first = message("first")
        val last = message("last")
        val original = Conversation.ofId(Uuid.random(), Uuid.random()).updateCurrentMessages(listOf(first, last))
        val updated = original.updateCurrentMessages(listOf(first.copy(parts = listOf(UIMessagePart.Text("changed")))))
        assertEquals(2, updated.messageNodes.size)
        assertSame(original.messageNodes[1], updated.messageNodes[1])
    }

    @Test
    fun updatingHiddenBranchDoesNotSelectItAndNewIdDoes() {
        val selected = message("selected")
        val hidden = message("hidden")
        val node = selected.toMessageNode().copy(messages = listOf(selected, hidden), selectIndex = 0, isFavorite = true)
        val original = Conversation.ofId(Uuid.random(), Uuid.random()).copy(messageNodes = listOf(node))
        val changed = hidden.copy(usage = TokenUsage(completionTokens = 7))
        val updated = original.updateCurrentMessages(listOf(changed))
        assertEquals(0, updated.messageNodes.single().selectIndex)
        assertSame(selected, updated.messageNodes.single().messages[0])
        assertSame(changed, updated.messageNodes.single().messages[1])
        assertTrue(updated.messageNodes.single().isFavorite)
        val next = message("new branch")
        val appended = updated.updateCurrentMessages(listOf(next)).messageNodes.single()
        assertEquals(node.id, appended.id)
        assertEquals(2, appended.selectIndex)
        assertSame(next, appended.currentMessage)
        assertSame(selected, original.messageNodes.single().currentMessage)
    }
}
