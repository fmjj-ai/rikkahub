package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.*
import org.junit.Test

class StructuredCompressPromptTest {
    @Test
    fun `small summary does not pay for seven mandatory headings`() {
        val prompt = buildCompressPrompt("保留待办", "简体中文", 98)
        assertTrue(prompt.contains("at most 98 tokens"))
        assertTrue(prompt.contains("no section headings"))
        assertTrue(prompt.contains("保留待办"))
        assertFalse(prompt.contains("## User Goals"))
        assertFalse(prompt.contains("approximately"))
    }

    @Test
    fun `long history keeps structured summary and user requirements`() {
        val prompt = buildCompressPrompt("保留文件路径", "简体中文", 2_000)
        assertTrue(prompt.contains("at most 2000 tokens"))
        assertTrue(prompt.contains("## User Goals"))
        assertTrue(prompt.contains("保留文件路径"))
    }
}
