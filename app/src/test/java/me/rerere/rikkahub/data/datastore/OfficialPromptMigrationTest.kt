package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEGACY_COMPRESS_PROMPT
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OfficialPromptMigrationTest {
    @Test
    fun `untouched built in prompt migrates to official date but retains all other settings`() {
        val default = DEFAULT_ASSISTANTS[1]
        val old = default.copy(
            name = "我的助手",
            systemPrompt = default.systemPrompt.replace("- Date: {{cur_date}}", "- Time: {{cur_datetime}}"),
        )
        val normalized = Settings(assistants = listOf(old), compressPrompt = LEGACY_COMPRESS_PROMPT).normalized()
        assertEquals(old.copy(systemPrompt = default.systemPrompt), normalized.assistants.single { it.id == old.id })
        assertEquals(DEFAULT_COMPRESS_PROMPT, normalized.compressPrompt)
        assertEquals(JsonInstant.encodeToString(normalized), JsonInstant.encodeToString(normalized.normalized()))
    }

    @Test
    fun `customized built in and user assistants keep their exact text`() {
        val default = DEFAULT_ASSISTANTS[1]
        val custom = default.copy(systemPrompt = default.systemPrompt + "\n用户自定义😀")
        assertSame(custom, custom.withOfficialDefaultPrompt())
        val other = default.copy(id = Uuid.random(), systemPrompt = "- Time: {{cur_datetime}}")
        assertSame(other, other.withOfficialDefaultPrompt())
        assertEquals("自定义压缩", officialDefaultCompressPrompt("自定义压缩"))
        assertEquals("", officialDefaultCompressPrompt(""))
    }
}
