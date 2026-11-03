package me.rerere.rikkahub.data.model

import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class MediaProviderConfigurationTest {
    @Test
    fun `validation trims text but keeps path case and stable ids`() {
        val provider = MediaGenerationProviderSetting.OpenAI(
            name = " 自定义 ", apiKey = " secret ", baseUrl = " https://example.com/Proxy/V1/ ",
            models = listOf(MediaGenerationModel(" test-model ", MediaKind.IMAGE), MediaGenerationModel(" ", MediaKind.IMAGE)),
        )
        val saved = provider.validated()
        assertEquals(provider.id, saved.id)
        assertEquals("https://example.com/Proxy/V1", saved.baseUrl)
        assertEquals("secret", saved.apiKey)
        assertEquals("自定义", saved.name)
        assertEquals(listOf(provider.models.first().id), saved.models.map { it.id })
        assertEquals("test-model", saved.models.single().modelId)
    }

    @Test
    fun `aliyun placeholder and workspace survive editing`() {
        val provider = MediaGenerationProviderSetting.Aliyun(workspaceId = " custom ")
        val saved = provider.validated() as MediaGenerationProviderSetting.Aliyun
        assertEquals(provider.baseUrl, saved.baseUrl)
        assertEquals("custom", saved.workspaceId)
    }

    @Test
    fun `invalid urls and unsupported kinds are rejected`() {
        listOf("file:///etc/file", "https://", "https://user:secret@example.com/v1", "https://example.com/v1?key=x")
            .forEach { url -> assertTrue(runCatching {
                MediaGenerationProviderSetting.OpenAI(baseUrl = url).validated()
            }.isFailure) }
        assertTrue(runCatching {
            MediaGenerationProviderSetting.OpenAI(models = listOf(MediaGenerationModel("video", MediaKind.VIDEO))).validated()
        }.isFailure)
    }

    @Test
    fun `saving a media draft preserves chat settings and other providers`() {
        val original = MediaGenerationProviderSetting.OpenAI()
        val other = MediaGenerationProviderSetting.MiniMax()
        val settings = Settings(mediaGenerationProviders = listOf(original, other), titlePrompt = "最新标题设置")
        val saved = settings.saveMediaProvider(original, original.copy(name = "新的名称"))
        assertEquals(settings.providers, saved.providers)
        assertEquals(settings.imageGenerationModelId, saved.imageGenerationModelId)
        assertEquals(settings.titlePrompt, saved.titlePrompt)
        assertEquals(other, saved.mediaGenerationProviders.last())
    }

    @Test
    fun `deleted or concurrently changed providers are not restored from stale draft`() {
        val original = MediaGenerationProviderSetting.OpenAI()
        assertTrue(runCatching { Settings().saveMediaProvider(original, original) }.isFailure)
        val changed = original.copy(apiKey = "new-key")
        assertTrue(runCatching {
            Settings(mediaGenerationProviders = listOf(changed)).saveMediaProvider(original, original.copy(name = "old"))
        }.isFailure)
    }

    @Test
    fun `duplicate model uuids are not silently discarded`() {
        val model = MediaGenerationModel("image", MediaKind.IMAGE)
        assertTrue(runCatching {
            MediaGenerationProviderSetting.OpenAI(models = listOf(model, model)).validated()
        }.isFailure)
    }

    @Test
    fun `same draft can be retried after its memory change has already applied`() {
        val original = MediaGenerationProviderSetting.OpenAI()
        val draft = original.copy(name = "已改内存但未落盘")
        val memory = Settings(mediaGenerationProviders = listOf(draft))
        assertEquals(memory, memory.saveMediaProvider(original, draft))
    }
}
