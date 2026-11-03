package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.Screen
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class MediaCreationNavigationTest {
    private val chat = Screen.Chat(Uuid.random().toString())

    @Test fun `notification startup keeps a root screen to return to`() {
        val sessionId = Uuid.random().toString()
        assertEquals(listOf(chat, Screen.MediaCreation(sessionId)), mediaCreationInitialScreens(chat, sessionId))
    }

    @Test fun `missing or invalid session ids preserve ordinary startup`() {
        listOf(null, "", "not-a-uuid").forEach {
            assertEquals(listOf(chat), mediaCreationInitialScreens(chat, it))
        }
    }
}
