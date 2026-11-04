package me.rerere.rikkahub

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyMediaNavigationTest {
    @Test
    fun `old media navigation keys remain readable without the removed feature`() {
        val session = Screen.MediaCreation("old-session")
        assertEquals(session, JsonInstant.decodeFromString<Screen.MediaCreation>(JsonInstant.encodeToString(session)))
        val provider = Screen.SettingMediaProviderDetail("old-provider")
        assertEquals(provider, JsonInstant.decodeFromString<Screen.SettingMediaProviderDetail>(
            JsonInstant.encodeToString(provider)))
        assertEquals(Screen.SettingMedia, JsonInstant.decodeFromString<Screen.SettingMedia>(
            JsonInstant.encodeToString(Screen.SettingMedia)))
    }
}
