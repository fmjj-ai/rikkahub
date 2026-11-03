package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import me.rerere.ai.core.ReasoningLevel
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SettingsFieldsTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `every serializable field has exactly one storage mapping`() {
        assertEquals(
            Settings.serializer().descriptor.elementNames.sorted(),
            SETTINGS_FIELDS.map { it.property.name }.sorted(),
        )
        assertEquals(SETTINGS_FIELDS.size, SETTINGS_FIELDS.map { it.key.name }.distinct().size)
    }

    @Test
    fun `custom models old title selection and media providers survive round trip`() {
        val original = Settings(
            titleModelId = Uuid.random(), suggestionModelId = Uuid.random(),
            fastModelReasoningLevel = ReasoningLevel.MAX,
            compressPrompt = "自定义压缩中文😀", compressModelId = Uuid.random(), launchCount = 73,
            mediaGenerationProviders = listOf(
                MediaGenerationProviderSetting.OpenAI(apiKey = "openai-key"),
                MediaGenerationProviderSetting.Aliyun(workspaceId = "工作区"),
                MediaGenerationProviderSetting.Volcengine(), MediaGenerationProviderSetting.MiniMax(),
                MediaGenerationProviderSetting.OpenRouter(),
            ),
            uploadS3Config = S3Config(endpoint = "https://upload.example/CaseSensitive", bucket = "media"),
        )
        val preferences = mutablePreferencesOf().apply { putSettings(original) }
        // 描述是 @Transient 函数，部分搜索选项也使用引用相等；比较全部持久化字段而非运行时对象身份。
        assertPersistedEquals(original, preferences.toSettings())
        assertPersistedEquals(original, JsonInstant.decodeFromString<Settings>(
            JsonInstant.encodeToString(original),
        ))
        assertEquals("MAX", preferences[SettingsStore.FAST_MODEL_REASONING_LEVEL])
        assertEquals(original.titleModelId.toString(), preferences[SettingsStore.TITLE_MODEL])
    }

    @Test
    fun `new fields do not rewrite old provider defaults`() {
        val settings = emptyPreferences().toSettings().normalized()
        assertEquals(DEFAULT_PROVIDERS, settings.providers)
        assertEquals(DEFAULT_AUTO_MODEL_ID, settings.compressModelId)
        assertTrue(settings.mediaGenerationProviders.isEmpty())
        assertFalse(settings.displaySetting.showUpdates)
        assertEquals(1_000_000, me.rerere.ai.provider.Model().contextWindowTokens)
        assertEquals(472_000, me.rerere.ai.provider.Model().autoCompressThresholdTokens)
    }

    @Test
    fun `clearing nullable fields removes old keys`() {
        val settings = Settings(titleModelId = Uuid.random(), suggestionModelId = Uuid.random())
        val preferences = mutablePreferencesOf().apply { putSettings(settings) }
        preferences.putSettings(settings.copy(titleModelId = null, suggestionModelId = null), settings)
        assertFalse(SettingsStore.TITLE_MODEL in preferences)
        assertFalse(SettingsStore.SUGGESTION_MODEL in preferences)
    }

    @Test
    fun `unknown reasoning levels fall back to auto and empty search list is valid`() {
        assertEquals(ReasoningLevel.AUTO, mutablePreferencesOf(
            SettingsStore.FAST_MODEL_REASONING_LEVEL to "UNKNOWN",
        ).toSettings().fastModelReasoningLevel)
        val settings = Settings(searchServices = emptyList(), searchServiceSelected = -1).normalized()
        assertEquals(0, settings.searchServiceSelected)
        assertPersistedEquals(settings, mutablePreferencesOf().apply { putSettings(settings) }.toSettings())
    }

    @Test
    fun `multi protocol endpoint model ids and custom context limits are not replaced`() {
        val model = me.rerere.ai.provider.Model(
            modelId = "custom-model", protocol = me.rerere.ai.provider.ProviderProtocol.CLAUDE,
            contextWindowTokens = 800_000, autoCompressThresholdTokens = 330_000,
        )
        val provider = me.rerere.ai.provider.ProviderSetting.MultiProtocol(
            name = "多协议", baseUrl = "https://example.com/CaseSensitive/v1", models = listOf(model),
            defaultProtocol = me.rerere.ai.provider.ProviderProtocol.CLAUDE,
            claude = me.rerere.ai.provider.ProviderSetting.Claude(apiKey = "test-claude-key"),
        )
        val settings = Settings(providers = listOf(provider), favoriteModels = listOf(model.id)).normalized()
        val restored = mutablePreferencesOf().apply { putSettings(settings) }.toSettings().normalized()
        assertPersistedEquals(settings, restored)
        val restoredProvider = restored.providers.find { it.id == provider.id }
            as me.rerere.ai.provider.ProviderSetting.MultiProtocol
        assertEquals(provider.baseUrl, restoredProvider.baseUrl)
        assertEquals(provider.defaultProtocol, restoredProvider.defaultProtocol)
        assertEquals(provider.claude?.apiKey, restoredProvider.claude?.apiKey)
        assertEquals(model, restoredProvider.models.single())
        assertEquals(model.id, restored.favoriteModels.single())
    }

    private fun assertPersistedEquals(expected: Settings, actual: Settings) {
        assertEquals(
            JsonInstant.parseToJsonElement(JsonInstant.encodeToString(expected)),
            JsonInstant.parseToJsonElement(JsonInstant.encodeToString(actual)),
        )
    }
}
