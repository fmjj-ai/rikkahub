package me.rerere.rikkahub.web.routes

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class AccountSettingsEventTest {
    @Test
    fun `web event removes account tokens including model overrides without changing stored settings`() {
        val account = ProviderSetting.ChatGPT(credential = OAuthCredential("private-access", "private-refresh"))
        val api = ProviderSetting.OpenAI(models = listOf(Model(modelId = "test", providerOverwrite = account)))
        val settings = Settings(providers = listOf(account, api))
        val web = settings.withoutAccountCredentials()
        val encoded = JsonInstant.encodeToString(web)
        assertFalse(encoded.contains("private-access"))
        assertFalse(encoded.contains("private-refresh"))
        assertEquals(account.id, web.providers.first().id)
        assertEquals("test", web.providers[1].models.single().modelId)
        assertNotNull((settings.providers.first() as ProviderSetting.ChatGPT).credential)
    }
}
