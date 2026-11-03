package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class FastModelSelectionTest {
    @Test
    fun `legacy title and suggestion choices do not override the fast model`() {
        val fast = Model(modelId = "fast")
        val old = Model(modelId = "old-title")
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(fast, old))),
            fastModelId = fast.id,
            titleModelId = old.id,
            suggestionModelId = old.id,
        )

        assertEquals(fast, settings.requireFastModel())
        assertEquals(old.id, settings.titleModelId)
        assertEquals(old.id, settings.suggestionModelId)
    }

    @Test(expected = IllegalStateException::class)
    fun `missing fast model does not silently fall back to a legacy title model`() {
        val old = Model(modelId = "old-title")
        Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(old))),
            fastModelId = Uuid.random(),
            titleModelId = old.id,
        ).requireFastModel()
    }

    @Test(expected = IllegalStateException::class)
    fun `image model cannot be used for title generation`() {
        val image = Model(modelId = "image", type = ModelType.IMAGE)
        Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(image))),
            fastModelId = image.id,
        ).requireFastModel()
    }
}
