package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderProtocol
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderMergeTest {
    @Test
    fun `merge typed providers maps protocols models and keys`() {
        val gpt = Model(id = Uuid.random(), modelId = "gpt-4o", displayName = "GPT-4o")
        val claude = Model(id = Uuid.random(), modelId = "claude-sonnet", displayName = "Claude")
        val primary = ProviderSetting.OpenAI(
            name = "万物",
            apiKey = "openai-key",
            baseUrl = "https://www.aiwanwu.cc/v1",
            models = listOf(gpt),
        )
        val other = ProviderSetting.Claude(
            name = "万物 Claude",
            apiKey = "claude-key",
            baseUrl = "https://www.aiwanwu.cc/v1",
            models = listOf(claude),
        )

        val plan = buildProviderMergePlan(primary, listOf(other))
        assertTrue(plan.conflicts.isEmpty())

        val merged = plan.merged()

        assertEquals(primary.id, merged.id)
        assertEquals("万物", merged.name)
        assertEquals("https://www.aiwanwu.cc/v1", merged.baseUrl)
        assertEquals("openai-key", merged.openai?.apiKey)
        assertEquals("claude-key", merged.claude?.apiKey)
        assertEquals(ProviderProtocol.OPENAI, merged.models.first { it.modelId == "gpt-4o" }.protocol)
        assertEquals(ProviderProtocol.CLAUDE, merged.models.first { it.modelId == "claude-sonnet" }.protocol)
    }

    @Test
    fun `same protocol conflict can keep the other key and dedupes models`() {
        val primaryModel = Model(id = Uuid.random(), modelId = "gpt-4o", displayName = "GPT-4o")
        val otherModel = Model(id = Uuid.random(), modelId = "gpt-4o", displayName = "GPT-4o copy")
        val primary = ProviderSetting.OpenAI(
            name = "主站",
            apiKey = "key-a",
            baseUrl = "https://relay.example.com/v1",
            models = listOf(primaryModel),
        )
        val other = ProviderSetting.OpenAI(
            name = "副站",
            apiKey = "key-b",
            baseUrl = "https://relay.example.com/v1",
            models = listOf(otherModel),
        )

        val plan = buildProviderMergePlan(primary, listOf(other))
        val conflict = plan.conflicts.single()
        assertEquals(ProviderProtocol.OPENAI, conflict.protocol)
        assertEquals(2, conflict.options.size)

        val chooseOther = conflict.options.first { it.source.id == other.id }
        val merged = plan.merged(mapOf(ProviderProtocol.OPENAI to chooseOther))

        assertEquals("key-b", merged.openai?.apiKey)
        assertEquals(1, merged.models.size)
        assertEquals(ProviderProtocol.OPENAI, merged.models.single().protocol)
    }

    @Test
    fun `merge keeps primary base url and multi endpoints`() {
        val openAi = Model(
            id = Uuid.random(),
            modelId = "gpt-4o",
            displayName = "GPT-4o",
            protocol = ProviderProtocol.OPENAI,
        )
        val claude = Model(
            id = Uuid.random(),
            modelId = "claude-sonnet",
            displayName = "Claude",
            protocol = ProviderProtocol.CLAUDE,
        )
        val primary = ProviderSetting.MultiProtocol(
            name = "万物",
            baseUrl = "https://primary.example.com/v1",
            openai = ProviderSetting.OpenAI(apiKey = "openai-key", baseUrl = "https://primary.example.com/v1"),
            models = listOf(openAi),
        )
        val other = ProviderSetting.MultiProtocol(
            name = "万物 2",
            baseUrl = "https://secondary.example.com/v1",
            claude = ProviderSetting.Claude(apiKey = "claude-key", baseUrl = "https://secondary.example.com/v1"),
            models = listOf(claude),
        )

        val merged = buildProviderMergePlan(primary, listOf(other)).merged()

        assertEquals("https://primary.example.com/v1", merged.baseUrl)
        assertEquals("https://primary.example.com/v1", merged.openai?.baseUrl)
        assertEquals("https://primary.example.com/v1", merged.claude?.baseUrl)
        assertEquals("claude-key", merged.claude?.apiKey)
        assertEquals(setOf("gpt-4o", "claude-sonnet"), merged.models.map { it.modelId }.toSet())
    }
}
