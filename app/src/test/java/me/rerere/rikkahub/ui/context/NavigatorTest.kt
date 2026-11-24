package me.rerere.rikkahub.ui.context

import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigatorTest {
    @Test
    fun `unanchored entry retains its origin for return and clears it on normal reopen`() {
        val source = Screen.Setting
        val destination = Screen.SettingProvider
        val stack = mutableListOf<NavKey>(source)
        val origins = mutableMapOf<String, String>()
        val navigator = Navigator(stack, origins)

        navigator.navigateWithoutAnchor(destination)
        assertEquals(listOf(source, destination), stack)
        assertEquals(source.toString(), origins[destination.toString()])
        navigator.popBackStack()
        assertEquals(source.toString(), origins[destination.toString()])

        navigator.navigate(destination)
        assertEquals(listOf(source, destination), stack)
        assertFalse(destination.toString() in origins)
    }
}
