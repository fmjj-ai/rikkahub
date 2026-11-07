package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class FormerPresetCompatibilityTest {
    @Test
    fun `removed presets are no longer part of the built in provider list`() {
        val removedIds = setOf(
            "15bce81b-3f95-4c9c-a8bf-68d47c010101",
            "15bce81b-3f95-4c9c-a8bf-68d47c010102",
            "15bce81b-3f95-4c9c-a8bf-68d47c010103",
        )
        assertFalse(DEFAULT_PROVIDERS.any { it.id.toString() in removedIds })
    }

    @Test
    fun `legacy preset field is ignored without deleting saved provider settings`() {
        val original: ProviderSetting = ProviderSetting.OpenAI(
            id = Uuid.parse("15bce81b-3f95-4c9c-a8bf-68d47c010101"),
            name = "已保存的自定义服务",
            apiKey = "saved-test-key",
            baseUrl = "https://relay.example/v1",
            models = listOf(Model(modelId = "saved-model")),
        )
        val serialized = JsonInstant.encodeToString(original)
        val legacy = serialized.replaceFirst("{", "{\"relayPresetId\":\"legacy\",")
        val decoded = JsonInstant.decodeFromString<ProviderSetting>(legacy)
        val normalized = Settings(providers = listOf(decoded)).normalized()
        val restored = normalized.providers.single { it.id == original.id }
        assertEquals(serialized, JsonInstant.encodeToString(restored))
        assertFalse(restored.builtIn)
    }
}
