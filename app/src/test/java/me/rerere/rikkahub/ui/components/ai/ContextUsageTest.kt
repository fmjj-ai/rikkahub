package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.ui.ContextUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageTest {
    @Test
    fun `zero cache hits display zero percent rather than a dash`() {
        assertEquals("0%（0 / 100）", formatCacheHitRate(ContextUsage(inputTokens = 100)))
        assertEquals("0%", formatCacheHitRate(ContextUsage()))
        assertEquals("0%", formatCacheHitRate(ContextUsage(inputTokens = -1)))
    }

    @Test
    fun `cache fractions remain available and invalid provider values are clamped`() {
        assertEquals("85%（85 / 100）", formatCacheHitRate(ContextUsage(inputTokens = 100, cachedTokens = 85)))
        assertEquals("100%（200 / 200）", formatCacheHitRate(ContextUsage(inputTokens = 100, cachedTokens = 200)))
        assertEquals("0%（0 / 100）", formatCacheHitRate(ContextUsage(inputTokens = 100, cachedTokens = -20)))
    }
}
