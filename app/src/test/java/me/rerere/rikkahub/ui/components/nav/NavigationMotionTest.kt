package me.rerere.rikkahub.ui.components.nav

import me.rerere.rikkahub.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMotionTest {
    @Test
    fun `provider detail expands only from its list`() {
        val detail = expandableNavigationMetadata(
            route = Screen.SettingProviderDetail("provider-1"),
            source = Screen.SettingProvider,
        )

        assertTrue(canExpandNavigation(navigationScreenMetadata(Screen.SettingProvider), detail))
        assertFalse(canExpandNavigation(navigationScreenMetadata(Screen.SettingModels), detail))
        assertFalse(canExpandNavigation(null, detail))
    }

    @Test
    fun `assistant settings match the exact assistant detail route`() {
        val first = Screen.AssistantDetail("assistant-1")
        val second = Screen.AssistantDetail("assistant-2")
        val prompt = expandableNavigationMetadata(Screen.AssistantPrompt(first.id), first)

        assertTrue(canExpandNavigation(navigationScreenMetadata(first), prompt))
        assertFalse(canExpandNavigation(navigationScreenMetadata(second), prompt))
        assertFalse(canExpandNavigation(navigationScreenMetadata(first), null))
    }
}
