package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.time.Instant

class OutputMessageProjectionTest {
    private fun message(text: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))
    private fun text(message: UIMessage) = (message.parts.last() as UIMessagePart.Text).text

    @Test
    fun stableHistoryIsProjectedOnceButNewTailMetadataIsNotSkipped() = runBlocking {
        var calls = 0
        val projection = OutputMessageProjection(true, { it }, { messages ->
            messages.map { calls++; it.copy(parts = listOf(UIMessagePart.Text(text(it) + "!"))) }
        })
        val history = message("history")
        val tail = message("tail")
        val first = projection.project(listOf(history, tail))
        val changed = tail.copy(usage = TokenUsage(completionTokens = 5))
        val second = projection.project(listOf(history, changed))
        assertEquals(3, calls)
        assertSame(first.visualMessages[0], second.visualMessages[0])
        assertSame(changed, second.messages[1])
        assertEquals(5, second.visualMessages[1].usage?.completionTokens)
        assertEquals("tail!", text(second.visualMessages[1]))
        assertEquals("tail", text(tail))
    }

    @Test
    fun insertedCheckpointIsAlignedByReferenceNotPosition() = runBlocking {
        var calls = 0
        val projection = OutputMessageProjection(true, { it }, { messages -> calls++; messages })
        val first = message("first")
        val tail = message("tail")
        projection.project(listOf(first, tail))
        val checkpoint = message("checkpoint")
        val updated = projection.project(listOf(first, checkpoint, tail))
        assertEquals(3, calls)
        assertSame(checkpoint, updated.visualMessages[1])
        assertSame(tail, updated.visualMessages[2])
    }

    @Test
    fun fullChangedTextIsReprocessedForCrossChunkRegex() = runBlocking {
        val projection = OutputMessageProjection(true, { it }, { messages ->
            messages.map { it.copy(parts = listOf(UIMessagePart.Text(text(it).replace(Regex("^AB$"), "matched")))) }
        })
        val first = message("A")
        assertEquals("A", text(projection.project(listOf(first)).visualMessages.single()))
        assertEquals("matched", text(projection.project(listOf(first.copy(
            parts = listOf(UIMessagePart.Text("AB")),
        ))).visualMessages.single()))
    }

    @Test
    fun unknownTransformersKeepWholeBatchSemantics() = runBlocking {
        assertFalse(object : OutputMessageTransformer {}.supportsSingleMessageProjection)
        val projection = OutputMessageProjection(false, { messages ->
            messages.mapIndexed { index, message -> message.copy(parts = listOf(UIMessagePart.Text("$index"))) }
        }, { it.reversed() })
        val updated = projection.project(listOf(message("a"), message("b")))
        assertEquals(listOf("1", "0"), updated.visualMessages.map(::text))
    }

    @Test
    fun newStepUsesItsOwnContextAndDoesNotReuseOldProjection() = runBlocking {
        val raw = message("text")
        fun step(suffix: String) = OutputMessageProjection(true, { it }, { messages ->
            messages.map { it.copy(parts = listOf(UIMessagePart.Text(text(it) + suffix))) }
        })
        assertEquals("text!", text(step("!").project(listOf(raw)).visualMessages.single()))
        assertEquals("text?", text(step("?").project(listOf(raw)).visualMessages.single()))
    }

    @Test
    fun thinkProjectionKeepsUnchangedMessagesAndHandlesOpenAndClosedTags() {
        val now = Instant.fromEpochMilliseconds(1_000)
        val plain = message("plain text")
        assertSame(plain, projectThinkTags(plain, now, complete = false))
        assertSame(plain, projectAssistantRegexes(plain, Assistant()))
        val open = projectThinkTags(message("<think>reason"), now, complete = false)
        assertNull((open.parts.first() as UIMessagePart.Reasoning).finishedAt)
        val closed = projectThinkTags(message("<think>reason</think>answer"), now, complete = false)
        assertEquals(now, (closed.parts.first() as UIMessagePart.Reasoning).finishedAt)
        assertEquals("answer", text(closed))
        val completed = projectThinkTags(message("<think>reason"), now, complete = true)
        assertEquals(now, (completed.parts.first() as UIMessagePart.Reasoning).finishedAt)
    }
}
