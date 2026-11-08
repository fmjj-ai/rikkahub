package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantDetailSnapshotTest {
    @Test
    fun firstFrameReusesTheCurrentAssistantConfiguration() {
        val expected = Assistant(id = Uuid.random(), name = "当前助手")
        val settings = Settings(assistants = listOf(expected))
        assertSame(expected, resolveDetailAssistant(settings, expected.id))
    }

    @Test
    fun missingAssistantKeepsTheRouteIdAcrossSnapshots() {
        val id = Uuid.random()
        val settings = Settings(assistants = emptyList())
        assertEquals(id, resolveDetailAssistant(settings, id).id)
        assertEquals(id, resolveDetailAssistant(settings, id).id)
    }
}
