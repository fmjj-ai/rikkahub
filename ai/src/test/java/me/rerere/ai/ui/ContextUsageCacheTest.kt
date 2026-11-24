package me.rerere.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextUsageCacheTest {
    @Test
    fun `known input with no cache hits returns zero`() {
        assertEquals(0f, ContextUsage(inputTokens = 100).cacheHitRate!!, 0f)
        assertEquals(0f, ContextUsage(inputTokens = 100, cachedTokens = -1).cacheHitRate!!, 0f)
    }

    @Test
    fun `cache ratio remains bounded and missing input has no denominator`() {
        assertEquals(0.85f, ContextUsage(inputTokens = 100, cachedTokens = 85).cacheHitRate!!, 0.001f)
        assertEquals(1f, ContextUsage(inputTokens = 100, cachedTokens = 200).cacheHitRate!!, 0f)
        assertNull(ContextUsage().cacheHitRate)
        assertNull(ContextUsage(inputTokens = -1, cachedTokens = 100).cacheHitRate)
    }
}
