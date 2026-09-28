package me.rerere.ai.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.util.json
import org.junit.Assert.*
import org.junit.Test

class ContextWindowTest {
    private val model = Model(modelId = "test")

    @Test
    fun `old model settings receive defaults and custom values survive serialization`() {
        val old = json.decodeFromString<Model>("""{"modelId":"old"}""")
        assertEquals(472_000, old.contextWindow)
        assertEquals(272_000, old.autoCompressThreshold)
        val custom = old.copy(contextWindowTokens = 164_000, autoCompressThresholdTokens = 100_000)
        assertEquals(custom, json.decodeFromString<Model>(json.encodeToString(custom)))
        assertFalse(custom.copy(autoCompressThresholdTokens = 200_000).hasValidContextSettings)
        assertFalse(custom.copy(contextWindowTokens = 0).hasValidContextSettings)
        assertTrue(custom.hasValidContextSettings)
    }

    @Test
    fun `invalid imported settings use finite positive budgets`() {
        val invalid = model.copy(contextWindowTokens = -1, autoCompressThresholdTokens = Int.MAX_VALUE)
        assertEquals(472_000, invalid.contextWindow)
        assertEquals(invalid.contextWindow, invalid.autoCompressThreshold)
    }

    @Test
    fun `compression replaces only request context and preserves original history`() {
        val messages = history()
        val compressed = compact(messages, 1)
        assertEquals(messages.map { it.parts }, compressed.map { it.parts })
        assertEquals(messages.map { it.id }, compressed.map { it.id })
        val active = compressed.contextMessages()
        assertEquals(3, active.size)
        assertEquals("简短摘要", active.first().toText())
        assertEquals(messages.takeLast(2), active.takeLast(2))
        assertTrue(active.estimateTokens() < messages.estimateTokens())
        val restored = json.decodeFromString<List<UIMessage>>(json.encodeToString(compressed))
        assertEquals(active.map { it.parts }, restored.contextMessages().map { it.parts })
    }

    @Test
    fun `editing or switching an earlier branch invalidates its summary`() {
        val compressed = compact(history(), 1)
        val edited = compressed.toMutableList().apply { this[0] = this[0].copy(parts = listOf(UIMessagePart.Text("修改"))) }
        assertEquals(edited, edited.contextMessages())
        val branched = compressed.toMutableList().apply { this[0] = UIMessage.user(this[0].toText()) }
        assertEquals(branched, branched.contextMessages())
    }

    @Test
    fun `translation and usage changes do not invalidate history summaries`() {
        val compressed = compact(history(), 1).toMutableList()
        compressed[0] = compressed[0].copy(translation = "translation", usage = TokenUsage(promptTokens = 42))
        assertEquals("简短摘要", compressed.contextMessages().first().toText())
    }

    @Test
    fun `new compaction reuses prior summary without resurrecting compressed history`() {
        val compressed = compact(history(), 1) + UIMessage.user("第三轮")
        val target = compressed.compressionTarget()!!
        assertEquals(3, target)
        val twice = compact(compressed, target)
        assertEquals(2, twice.contextMessages().size)
        assertNull(twice.compressionTarget())
    }

    @Test
    fun `latest user turn and pending or completed tools are never split`() {
        val tool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("id", "test", "{}")),
        )
        val messages = history().take(3) + List(10) { tool }
        assertEquals(1, messages.compressionTarget(1))
        assertNull(listOf(UIMessage.user("only"), tool).compressionTarget())
    }

    @Test
    fun `used is latest input plus output and does not sum repeated prompt usage`() {
        val input = history().take(3).toMutableList()
        input[1] = input[1].copy(usage = TokenUsage(promptTokens = 200_000, completionTokens = 1_000))
        val output = UIMessage.assistant("response").copy(
            modelId = model.id,
            usage = TokenUsage(promptTokens = 230_000, completionTokens = 2_000),
            contextInputHash = input.contextFingerprint(),
        )
        val usage = (input + output).contextUsage(model)
        assertEquals(232_000, usage.usedTokens)
        assertEquals(230_000, usage.inputTokens)
        assertEquals(2_000, usage.outputTokens)
        assertFalse(usage.estimated)
        val afterInput = (input + output + UIMessage.user("new message")).contextUsage(model)
        assertTrue(afterInput.usedTokens > usage.usedTokens)
        assertTrue(afterInput.estimated)
    }

    @Test
    fun `compression clears stale context occupancy even when retained response has old usage`() {
        val input = history().take(3)
        val output = UIMessage.assistant("response").copy(
            modelId = model.id,
            usage = TokenUsage(promptTokens = 230_000, completionTokens = 2_000),
            contextInputHash = input.contextFingerprint(),
        )
        val messages = compact(input + output, 1)
        val usage = messages.contextUsage(model)
        assertTrue(usage.usedTokens < 230_000)
        assertEquals(230_000, usage.inputTokens)
        assertTrue(usage.estimated)
    }

    @Test
    fun `model switching and edited history fall back to current context estimate`() {
        val input = listOf(UIMessage.user("original"))
        val output = UIMessage.assistant("response").copy(
            modelId = model.id,
            usage = TokenUsage(promptTokens = 100_000),
            contextInputHash = input.contextFingerprint(),
        )
        assertTrue((input + output).contextUsage(Model()).usedTokens < 100_000)
        assertTrue((listOf(UIMessage.user("edited")) + output).contextUsage(model).usedTokens < 100_000)
    }

    @Test
    fun `splitting retains Unicode and all long content`() {
        val text = "中文、日本語😀 abc ".repeat(20_000)
        val chunks = text.splitForCompression(164)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.estimateTokens() <= 164 })
        assertTrue(chunks.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
    }

    @Test
    fun `compression includes long text tail and tool results`() {
        val text = "x".repeat(5_000) + "必须保留的尾部"
        val message = UIMessage.assistant(text).copy(parts = listOf(
            UIMessagePart.Text(text),
            UIMessagePart.Tool("id", "read", "{path:test}", listOf(UIMessagePart.Text("工具结果"))),
        ))
        assertTrue(message.compressionText().contains("必须保留的尾部"))
        assertTrue(message.compressionText().contains("工具结果"))
    }

    @Test
    fun `all chunks are summarized then merged`() = runBlocking {
        val source = "abcdefghijklmnopqrstuvwx"
        val requests = mutableListOf<String>()
        val result = compressContextText(source, 2) {
            requests += it
            "s"
        }
        assertEquals(source, requests.take(3).joinToString(""))
        assertEquals("s", result)
    }

    @Test
    fun `empty non shrinking and failed summaries never produce compaction`() = runBlocking {
        for (result in listOf("", "unchanged text")) {
            val failure = runCatching { compressContextText("unchanged text", 100) { result } }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
        val failure = runCatching { compressContextText("original text", 100) { error("network") } }.exceptionOrNull()
        assertEquals("network", failure?.message)
    }

    @Test
    fun `cancellation stops compression immediately`() = runBlocking {
        var requests = 0
        val failure = runCatching {
            compressContextText("x".repeat(100), 1) {
                requests++
                throw CancellationException("cancel")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, requests)
    }

    private fun history() = listOf(
        UIMessage.user("earlier content ".repeat(100)),
        UIMessage.assistant("earlier answer ".repeat(100)),
        UIMessage.user("latest question"),
        UIMessage.assistant("latest answer"),
    )

    private fun compact(messages: List<UIMessage>, boundary: Int): List<UIMessage> = messages.mapIndexed { index, message ->
        if (index == boundary) message.copy(contextCompaction = ContextCompaction(
            summary = "简短摘要",
            prefixHash = messages.take(boundary + 1).contextFingerprint(),
        )) else message
    }
}
