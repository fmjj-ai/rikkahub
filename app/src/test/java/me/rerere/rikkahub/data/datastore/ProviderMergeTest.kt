package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderProtocol
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun `same protocol conflict can choose key without losing independently referenced model ids`() {
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
        assertEquals(2, merged.models.size)
        assertEquals(setOf(primaryModel.id, otherModel.id), merged.models.map { it.id }.toSet())
        assertTrue(merged.models.all { it.protocol == ProviderProtocol.OPENAI })
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

    @Test
    fun `url grouping canonicalizes host but keeps path case`() {
        val a = ProviderSetting.OpenAI(baseUrl = "HTTPS://Relay.Example:443/Api/V1/")
        val b = ProviderSetting.Claude(baseUrl = "https://relay.example/Api/V1")
        val different = b.copy(baseUrl = "https://relay.example/api/v1")
        assertEquals(a.mergeUrlIdentity(), b.mergeUrlIdentity())
        org.junit.Assert.assertNotEquals(a.mergeUrlIdentity(), different.mergeUrlIdentity())
    }

    @Test
    fun `same key and url with different request strategies still conflict`() {
        val a = ProviderSetting.OpenAI(apiKey = "same", baseUrl = "https://example.com/v1")
        listOf(a.copy(useResponseApi = true), a.copy(chatCompletionsPath = "/custom/chat"),
            a.copy(includeHistoryReasoning = false)).forEach { other ->
            assertEquals(2, buildProviderMergePlan(a, listOf(other)).conflicts.single().options.size)
        }
    }

    @Test
    fun `independent model ids keep references and distinct context settings`() {
        val aModel = Model(modelId = "same", contextWindowTokens = 1_000_000, autoCompressThresholdTokens = 472_000)
        val bModel = Model(modelId = "same", contextWindowTokens = 200_000, autoCompressThresholdTokens = 80_000)
        val a = ProviderSetting.OpenAI(models = listOf(aModel))
        val b = ProviderSetting.OpenAI(models = listOf(bModel))
        val settings = Settings(providers = listOf(a, b), favoriteModels = listOf(aModel.id, bModel.id),
            fastModelId = bModel.id, imageGenerationModelId = aModel.id,
            mediaGenerationProviders = listOf(me.rerere.mediagen.provider.MediaGenerationProviderSetting.MiniMax()))
        val merged = buildProviderMergePlan(a, listOf(b)).merged()
        val result = settings.applyProviderMerge(a, listOf(b), merged)
        assertEquals(settings.favoriteModels, result.favoriteModels)
        assertEquals(settings.fastModelId, result.fastModelId)
        assertEquals(settings.mediaGenerationProviders, result.mediaGenerationProviders)
        assertEquals(80_000, result.findModelById(bModel.id)?.autoCompressThresholdTokens)
        assertEquals(472_000, result.findModelById(aModel.id)?.autoCompressThresholdTokens)
    }

    @Test
    fun `same uuid with conflicting model configuration cannot be silently merged`() {
        val model = Model(modelId = "same")
        val a = ProviderSetting.OpenAI(models = listOf(model))
        val b = a.copy(id = Uuid.random(), models = listOf(model.copy(contextWindowTokens = 99_000)))
        assertTrue(runCatching { buildProviderMergePlan(a, listOf(b)).merged() }.isFailure)
    }

    @Test
    fun `stale merge cannot restore removed or edited providers`() {
        val a = ProviderSetting.OpenAI()
        val b = ProviderSetting.Claude()
        val merged = buildProviderMergePlan(a, listOf(b)).merged()
        assertTrue(runCatching { Settings(providers = listOf(a)).applyProviderMerge(a, listOf(b), merged) }.isFailure)
        assertTrue(runCatching {
            Settings(providers = listOf(a.copy(apiKey = "changed"), b)).applyProviderMerge(a, listOf(b), merged)
        }.isFailure)
    }

    @Test
    fun `identical accepted merge may retry persistence but may not overwrite subsequent changes`() {
        val a = ProviderSetting.OpenAI()
        val b = ProviderSetting.Claude()
        val merged = buildProviderMergePlan(a, listOf(b)).merged()
        val accepted = Settings(providers = listOf(a, b)).applyProviderMerge(a, listOf(b), merged)
        assertSame(accepted, accepted.applyProviderMerge(a, listOf(b), merged))
        assertTrue(runCatching {
            accepted.copy(providers = listOf(merged.copy(name = "external edit")))
                .applyProviderMerge(a, listOf(b), merged)
        }.isFailure)
    }
}
