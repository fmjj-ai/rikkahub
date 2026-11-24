package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.*
import org.junit.Test

class StructuredCompressPromptTest {
    @Test
    fun `official compression instructions preserve structure and user requirements`() {
        val prompt = buildCompressPrompt("保留待办", "简体中文")
        assertTrue(prompt.startsWith("Stop the current task and write a structured summary"))
        assertTrue(prompt.contains("Do not call any tools."))
        assertTrue(prompt.contains("保留待办"))
        assertTrue(prompt.contains("## User Goals"))
        assertTrue(prompt.contains("use 简体中文"))
        assertFalse(prompt.contains("approximately"))
    }

    @Test
    fun `empty extra instructions add no boilerplate and fallback template retains history`() {
        val prompt = buildCompressPrompt("", "简体中文")
        assertFalse(prompt.contains("Additional instructions from user:"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("{content}"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("## Current State"))
    }
}
