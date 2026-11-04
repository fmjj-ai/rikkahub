package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.estimateTokens
import org.junit.Assert.*
import org.junit.Test

class CompressionPolicyTest {
    @Test
    fun `summary length uses expanded document input and includes the checkpoint message overhead`() {
        val attachment = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Document("file:///document.txt", "document.txt"),
        ))
        val expanded = attachment.copy(parts = attachment.parts + UIMessagePart.Text("正文".repeat(1_000)))
        validateCompressionSummary("正文摘要".repeat(30), listOf(expanded).estimateTokens())
        assertThrows(IllegalStateException::class.java) {
            validateCompressionSummary("正文摘要".repeat(30), listOf(attachment).estimateTokens())
        }
        assertThrows(IllegalStateException::class.java) { validateCompressionSummary("甲乙丙丁", 8) }
        assertThrows(IllegalStateException::class.java) { validateCompressionSummary("", 100) }
    }

    @Test
    fun `short history gets a smaller ceiling instead of a fixed 2000 token summary`() {
        assertEquals(98, compressionSummaryTarget(2_000, 200))
        assertEquals(2_000, compressionSummaryTarget(2_000, 20_000))
        assertEquals(100, compressionSummaryTarget(100, 20_000))
        assertEquals(1, compressionSummaryTarget(0, 200))
        assertEquals(2_000, compressionSummaryTarget(2_000, Int.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trivial history is rejected before a paid request`() {
        compressionSummaryTarget(2_000, 8)
    }

    @Test
    fun `normal request fits exactly and excess uses the fallback before sending`() {
        assertTrue(canUseSingleCompressionRequest(1000, 100, 100, 1000, 2712))
        assertFalse(canUseSingleCompressionRequest(1001, 100, 100, 1000, 2712))
        assertFalse(canUseSingleCompressionRequest(Int.MAX_VALUE, Int.MAX_VALUE.toLong(), 100, 1000, Int.MAX_VALUE))
        assertFalse(canUseSingleCompressionRequest(1000, Long.MAX_VALUE, 100, 1000, 1_000_000))
    }

    @Test
    fun `invalid imported budgets are not treated as room for a single request`() {
        assertFalse(canUseSingleCompressionRequest(-1, 0, 0, 0, 1000))
        assertFalse(canUseSingleCompressionRequest(0, -1, 0, 0, 1000))
        assertFalse(canUseSingleCompressionRequest(0, 0, 0, 0, 0))
    }

    @Test
    fun `chat and summary share reasoning headers and priority policy`() {
        val model = Model(modelId = "chat", customHeaders = listOf(CustomHeader("X-Model", "model")))
        val assistant = Assistant(
            customHeaders = listOf(CustomHeader("X-Assistant", "assistant")),
            reasoningLevel = ReasoningLevel.MAX,
            enablePriorityTier = true,
        )
        val chat = chatGenerationParams(assistant, model, ProviderSetting.OpenAI(), emptyList())
        val summary = chat.copy(maxTokens = null)
        assertEquals(chat.customHeaders, summary.customHeaders)
        assertEquals(chat.tools, summary.tools)
        assertEquals(ReasoningLevel.MAX, summary.reasoningLevel)
        assertTrue(summary.serviceTierPriority)
        assertFalse(chatGenerationParams(assistant, model, ProviderSetting.Claude(), emptyList()).serviceTierPriority)
    }
}
