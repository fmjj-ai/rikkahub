package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamingReferenceTest {
    @Test
    fun signatureOnlyChunkKeepsHistoryAndReasoningText() {
        val history = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question")))
        val reasoning = UIMessagePart.Reasoning("thinking", finishedAt = null)
        val current = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning))
        val metadata = ClaudeReasoningMetadata(signature = "signature").toMetadata()
        val delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("", finishedAt = null, metadata = metadata),
        ))
        val result = listOf(history, current).handleMessageChunk(MessageChunk(
            id = "chunk",
            model = "model",
            choices = listOf(UIMessageChoice(index = 0, delta = delta, message = null, finishReason = null)),
        ))
        assertSame(history, result[0])
        val updated = result.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("thinking", updated.reasoning)
        assertEquals(metadata, updated.metadata)
    }

    @Test
    fun usageOnlyChunkDoesNotRebuildHistoryOrInventText() {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("answer"))))
        val chunk = MessageChunk("usage", "model", emptyList(), TokenUsage(completionTokens = 12))
        // 文本合并保持原状；GenerationHandler 随后独立处理该 chunk 的累计用量。
        assertSame(messages, messages.handleMessageChunk(chunk))
        assertEquals(12, chunk.usage?.completionTokens)
    }

    @Test
    fun finishingReasoningOnlyCopiesUnfinishedContent() {
        val plain = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("plain")))
        assertSame(plain, plain.finishReasoning())
        val metadata = ClaudeReasoningMetadata(signature = "signature").toMetadata()
        val pending = UIMessagePart.Reasoning("reason", finishedAt = null, metadata = metadata)
        val text = UIMessagePart.Text("answer")
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending, text))
        val completed = original.finishReasoning()
        assertNull(pending.finishedAt)
        assertNotNull((completed.parts.first() as UIMessagePart.Reasoning).finishedAt)
        assertSame(metadata, completed.parts.first().metadata)
        assertSame(text, completed.parts.last())
        assertSame(completed, completed.finishReasoning())
    }
}
