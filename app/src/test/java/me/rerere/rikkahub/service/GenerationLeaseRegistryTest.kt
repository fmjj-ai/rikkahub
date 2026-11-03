package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationLeaseRegistryTest {
    @Test
    fun `releasing an old task does not release the new task in the same conversation`() {
        val registry = GenerationLeaseRegistry()
        registry.acquire("old", "conversation")
        registry.acquire("new", "conversation")
        registry.release("old")

        assertFalse(registry.isEmpty)
        assertEquals(setOf("new"), registry.generationIds)
        assertEquals("conversation", registry.latestConversationId)
    }

    @Test
    fun `duplicate acquire and release are idempotent`() {
        val registry = GenerationLeaseRegistry()
        registry.acquire("task", "conversation")
        registry.acquire("task", "conversation")
        assertEquals(setOf("task"), registry.generationIds)

        registry.release("task")
        registry.release("task")
        assertTrue(registry.isEmpty)
        assertNull(registry.latestConversationId)
    }

    @Test
    fun `releasing the latest conversation restores the remaining notification target`() {
        val registry = GenerationLeaseRegistry()
        registry.acquire("first", "conversation-a")
        registry.acquire("second", "conversation-b")
        registry.release("unknown")
        assertEquals("conversation-b", registry.latestConversationId)

        registry.release("second")
        assertEquals("conversation-a", registry.latestConversationId)
        registry.clear()
        assertTrue(registry.generationIds.isEmpty())
    }

    @Test
    fun `timeout snapshot remains stable after clearing the registry`() {
        val registry = GenerationLeaseRegistry()
        registry.acquire("task", "conversation")
        val ids = registry.generationIds
        registry.clear()

        assertEquals(setOf("task"), ids)
        assertTrue(registry.isEmpty)
    }
}
