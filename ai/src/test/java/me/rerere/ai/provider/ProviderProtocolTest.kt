package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderProtocolTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `multi protocol serializes and restores each endpoint`() {
        val openAiModel = Model(
            id = Uuid.random(),
            modelId = "gpt-4o",
            displayName = "GPT-4o",
            protocol = ProviderProtocol.OPENAI,
        )
        val claudeModel = Model(
            id = Uuid.random(),
            modelId = "claude-sonnet-4-5",
            displayName = "Claude Sonnet",
            protocol = ProviderProtocol.CLAUDE,
        )
        val provider = ProviderSetting.MultiProtocol(
            id = Uuid.random(),
            name = "万物",
            baseUrl = "https://www.aiwanwu.cc/v1",
            defaultProtocol = ProviderProtocol.OPENAI,
            openai = ProviderSetting.OpenAI(apiKey = "openai-key", baseUrl = "https://www.aiwanwu.cc/v1"),
            claude = ProviderSetting.Claude(apiKey = "claude-key", baseUrl = "https://www.aiwanwu.cc/v1"),
            models = listOf(openAiModel, claudeModel),
        )

        val encoded = json.encodeToString(ProviderSetting.serializer(), provider)
        assertTrue(encoded.contains("\"type\":\"multi\""))

        val decoded = json.decodeFromString(ProviderSetting.serializer(), encoded)
        assertTrue(decoded is ProviderSetting.MultiProtocol)
        val multi = decoded as ProviderSetting.MultiProtocol
        assertEquals(provider.id, multi.id)
        assertEquals("万物", multi.name)
        assertEquals("https://www.aiwanwu.cc/v1", multi.baseUrl)
        assertEquals("openai-key", multi.openai?.apiKey)
        assertEquals("claude-key", multi.claude?.apiKey)
        assertEquals(
            listOf(ProviderProtocol.OPENAI, ProviderProtocol.CLAUDE),
            multi.models.map { it.protocol },
        )
    }

    @Test
    fun `toMultiProtocol keeps typed provider models and marks their protocol`() {
        val model = Model(modelId = "claude-sonnet-4-5", displayName = "Claude")
        val typed = ProviderSetting.Claude(
            name = "万物",
            apiKey = "claude-key",
            baseUrl = "https://www.aiwanwu.cc/v1",
            models = listOf(model),
        )

        val multi = typed.toMultiProtocol()

        assertEquals(ProviderProtocol.CLAUDE, multi.defaultProtocol)
        assertEquals(ProviderProtocol.CLAUDE, multi.models.single().protocol)
        assertEquals("claude-key", multi.claude?.apiKey)
        assertEquals("https://www.aiwanwu.cc/v1", multi.baseUrl)
        assertEquals(typed.id, multi.id)
    }

    @Test
    fun `configured protocols require a filled api key`() {
        val multi = ProviderSetting.MultiProtocol(
            name = "万物",
            baseUrl = "https://www.aiwanwu.cc/v1",
            openai = ProviderSetting.OpenAI(apiKey = "", baseUrl = "https://www.aiwanwu.cc/v1"),
            claude = ProviderSetting.Claude(apiKey = "claude-key", baseUrl = "https://www.aiwanwu.cc/v1"),
        )

        assertEquals(listOf(ProviderProtocol.CLAUDE), multi.configuredProtocols())
        assertTrue(multi.endpointOrNull(ProviderProtocol.OPENAI)?.isConfigured() == false)
        assertFalse(multi.endpointOrNull(ProviderProtocol.OPENAI)?.isConfigured() == true)
    }

    @Test
    fun `endpoint inherits parent url and identity`() {
        val multi = ProviderSetting.MultiProtocol(
            name = "万物",
            baseUrl = "https://shared.example.com/v1",
            openai = ProviderSetting.OpenAI(apiKey = "key", baseUrl = "https://stale.example.com/v1"),
        )

        val endpoint = multi.endpointOrNull(ProviderProtocol.OPENAI) as ProviderSetting.OpenAI

        assertEquals(multi.id, endpoint.id)
        assertEquals("万物", endpoint.name)
        assertEquals("https://shared.example.com/v1", endpoint.baseUrl)
        assertEquals("key", endpoint.apiKey)
    }

    @Test
    fun `protocol of typed provider follows its type`() {
        val model = Model(modelId = "m")
        assertEquals(
            ProviderProtocol.OPENAI,
            ProviderSetting.OpenAI().protocolOf(model),
        )
        assertEquals(
            ProviderProtocol.CLAUDE,
            ProviderSetting.Claude().protocolOf(model),
        )
        assertEquals(
            ProviderProtocol.GOOGLE,
            ProviderSetting.Google().protocolOf(model),
        )
        val multi = ProviderSetting.MultiProtocol(defaultProtocol = ProviderProtocol.CLAUDE)
        assertEquals(ProviderProtocol.CLAUDE, multi.protocolOf(model))
        assertEquals(
            ProviderProtocol.GOOGLE,
            multi.protocolOf(model.copy(protocol = ProviderProtocol.GOOGLE)),
        )
    }

    @Test
    fun `blank google key and service account is not configured`() {
        assertFalse(ProviderSetting.Google(apiKey = "").isConfigured())
        assertNull(ProviderProtocol.of(ProviderSetting.MultiProtocol()))
    }
}
