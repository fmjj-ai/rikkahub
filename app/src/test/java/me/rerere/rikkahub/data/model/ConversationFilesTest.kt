package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationFilesTest {
    private fun message(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun conversation(vararg messages: UIMessage) = Conversation.ofId(Uuid.random(), Uuid.random())
        .copy(messageNodes = messages.map { it.toMessageNode() })
    private fun tool(id: String, output: List<UIMessagePart>) = UIMessagePart.Tool(id, "tool", "{}", output)

    private class CountingParts(private val values: List<UIMessagePart>) : AbstractList<UIMessagePart>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): UIMessagePart { reads++; return values[index] }
    }

    @Test
    fun deletingNodeCollectsFourMediaKindsAndDeduplicatesUrls() {
        val original = conversation(message(
            UIMessagePart.Image("file:///image.png"),
            UIMessagePart.Video("file:///video.mp4"),
            UIMessagePart.Audio("file:///audio.mp3"),
            UIMessagePart.Document("file:///document.txt", "document.txt"),
            UIMessagePart.Image("file:///image.png"),
        ))
        val updated = original.copy(messageNodes = emptyList())
        assertEquals(setOf("file:///image.png", "file:///video.mp4", "file:///audio.mp3", "file:///document.txt"),
            updated.removedFileUrlsSince(original))
    }

    @Test
    fun anotherHiddenBranchProtectsSharedFile() {
        val file = UIMessagePart.Image("file:///shared.png")
        val original = conversation(message(file), message(UIMessagePart.Text("visible")))
        val holder = original.messageNodes[1].copy(messages = listOf(
            original.messageNodes[1].currentMessage, message(file),
        ))
        val before = original.copy(messageNodes = listOf(original.messageNodes[0], holder))
        assertTrue(before.copy(messageNodes = listOf(holder)).removedFileUrlsSince(before).isEmpty())
        val hiddenRemoved = before.copy(messageNodes = listOf(holder.copy(messages = holder.messages.take(1))))
        assertEquals(setOf("file:///shared.png"), hiddenRemoved.removedFileUrlsSince(before))
    }

    @Test
    fun nestedToolResultsAndMovingFilesAreCheckedRecursively() {
        val image = UIMessagePart.Image("file:///nested.png")
        val nested = tool("outer", listOf(tool("middle", listOf(tool("inner", listOf(image))))))
        val original = conversation(message(nested))
        assertEquals(setOf(image.url), original.copy(messageNodes = emptyList()).removedFileUrlsSince(original))
        val moved = original.copy(messageNodes = listOf(message(image).toMessageNode()))
        assertTrue(moved.removedFileUrlsSince(original).isEmpty())
    }

    @Test
    fun textAndToolInputUpdatesDoNotReadUnchangedAttachments() {
        val historyParts = CountingParts(listOf(UIMessagePart.Image("file:///history.png")))
        val toolParts = CountingParts(listOf(UIMessagePart.Document("file:///tool.txt", "tool.txt")))
        val history = UIMessage(role = MessageRole.ASSISTANT, parts = historyParts)
        val existingTool = tool("call", toolParts)
        val tail = message(UIMessagePart.Text("before"), existingTool)
        val original = conversation(history, tail)
        val changed = tail.copy(parts = listOf(UIMessagePart.Text("after"), existingTool.copy(input = "{\"new\":true}")))
        val updated = original.updateCurrentMessages(listOf(history, changed))
        assertTrue(updated.removedFileUrlsSince(original).isEmpty())
        assertEquals(0, historyParts.reads)
        assertEquals(0, toolParts.reads)
    }

    @Test
    fun equivalentMediaCopyAndReorderedNodesDoNotScanOtherHistory() {
        val untouched = CountingParts(listOf(UIMessagePart.Image("file:///untouched.png")))
        val history = UIMessage(role = MessageRole.ASSISTANT, parts = untouched)
        val image = UIMessagePart.Image("file:///same.png")
        val tail = message(image)
        val original = conversation(history, tail)
        val copied = original.updateCurrentMessages(listOf(history, tail.copy(parts = listOf(image.copy()))))
        assertTrue(copied.removedFileUrlsSince(original).isEmpty())
        assertTrue(original.copy(messageNodes = original.messageNodes.reversed()).removedFileUrlsSince(original).isEmpty())
        assertEquals(0, untouched.reads)
    }

    @Test
    fun preservesExistingLocalFileSchemeFilter() {
        val original = conversation(message(
            UIMessagePart.Image("file:/one-slash.png"),
            UIMessagePart.Image("content://provider/image"),
            UIMessagePart.Image("https://example.invalid/image.png"),
            UIMessagePart.Image("data:image/png;base64,AA=="),
        ))
        assertTrue(original.copy(messageNodes = emptyList()).removedFileUrlsSince(original).isEmpty())
    }
}
