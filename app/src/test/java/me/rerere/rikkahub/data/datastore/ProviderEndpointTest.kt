package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderProtocol
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderEndpointTest {
    private fun model(protocol: ProviderProtocol? = null): Model {
        return Model(
            id = Uuid.random(),
            modelId = "test-model",
            displayName = "Test Model",
            protocol = protocol,
        )
    }

    private fun multi(
        models: List<Model>,
        openAiKey: String = "openai-key",
        claudeKey: String = "claude-key",
    ): ProviderSetting.MultiProtocol {
        return ProviderSetting.MultiProtocol(
            name = "万物",
            baseUrl = "https://shared.example.com/v1",
            defaultProtocol = ProviderProtocol.OPENAI,
            openai = ProviderSetting.OpenAI(
                apiKey = openAiKey,
                baseUrl = "https://openai.example.com/v1",
            ),
            claude = ProviderSetting.Claude(
                apiKey = claudeKey,
                baseUrl = "https://claude.example.com/v1",
            ),
            models = models,
        )
    }

    @Test
    fun `explicit model protocol resolves matching endpoint`() {
        val model = model(ProviderProtocol.CLAUDE)
        val endpoint = model.resolveEndpoint(listOf(multi(listOf(model)))) as ProviderSetting.Claude

        assertEquals("claude-key", endpoint.apiKey)
        assertEquals("https://shared.example.com/v1", endpoint.baseUrl)
        assertEquals("万物", endpoint.name)
    }

    @Test
    fun `missing protocol falls back to provider default`() {
        val model = model(null)
        val endpoint = model.resolveEndpoint(listOf(multi(listOf(model)))) as ProviderSetting.OpenAI

        assertEquals("openai-key", endpoint.apiKey)
        assertEquals("https://shared.example.com/v1", endpoint.baseUrl)
    }

    @Test
    fun `unconfigured protocol does not resolve`() {
        val model = model(ProviderProtocol.CLAUDE)
        val provider = multi(listOf(model), claudeKey = "")

        assertNull(model.resolveEndpoint(listOf(provider)))
    }

    @Test
    fun `provider overwrite wins over multi protocol routing`() {
        val model = model(ProviderProtocol.OPENAI)
        val provider = multi(listOf(model))
        val override = ProviderSetting.Claude(
            apiKey = "override-key",
            baseUrl = "https://override.example.com/v1",
        )
        val overridden = model.copy(providerOverwrite = override)

        val endpoint = overridden.resolveEndpoint(listOf(provider)) as ProviderSetting.Claude

        assertEquals("override-key", endpoint.apiKey)
        assertEquals(ProviderProtocol.CLAUDE, overridden.effectiveProtocol(listOf(provider)))
    }

    @Test
    fun `typed provider keeps old behavior`() {
        val model = model(null)
        val provider = ProviderSetting.OpenAI(
            name = "Legacy",
            apiKey = "legacy-key",
            baseUrl = "https://legacy.example.com/v1",
            models = listOf(model),
        )

        val endpoint = model.resolveEndpoint(listOf(provider)) as ProviderSetting.OpenAI

        assertEquals("legacy-key", endpoint.apiKey)
        assertEquals(ProviderProtocol.OPENAI, model.effectiveProtocol(listOf(provider)))
    }
}
