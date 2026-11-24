package me.rerere.rikkahub.ui.components.visualization

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.visualization.*
import org.junit.Assert.*
import org.junit.Test

class VizFullscreenLayoutTest {
    private fun node(type: String, id: String, slot: String? = null, children: List<VizNode> = emptyList()) =
        VizNode(type, buildJsonObject {
            put("id", id)
            slot?.let { put("slot", it) }
        }, children)

    @Test fun nestedScenesRemainWholeAndEachControlHasExactlyOneLocation() {
        val first = node("scene", "scene:1").copy(shapes = listOf(VizShape("text", JsonObject(emptyMap()))))
        val second = node("scene", "scene:2")
        val row = node("row", "container", children = listOf(
            node("text", "info"), first, node("button", "next"),
            node("column", "nested", children = listOf(second, node("numberInput", "count"))),
        ))
        val layout = projectFullscreen(VizFrame(JsonObject(emptyMap()), listOf(row)))
        assertEquals(listOf(first, second), layout.scenes)
        assertSame(first, layout.scenes.first())
        fun leaves(node: VizNode): List<String> = if (node.children.isEmpty()) listOf(node.values.string("id"))
            else node.children.flatMap(::leaves)
        assertEquals(listOf("next"), layout.groups.getValue(VizControlGroup.Actions).flatMap(::leaves))
        assertEquals(listOf("count"), layout.groups.getValue(VizControlGroup.Parameters).flatMap(::leaves))
        assertEquals(listOf("info"), layout.groups.getValue(VizControlGroup.Information).flatMap(::leaves))
    }

    @Test fun explicitV3SlotsWinAndRetainContainerIdentityAndError() {
        val container = node("column", "controls", "controls", listOf(node("text", "step")))
            .copy(error = "partial")
        val layout = projectFullscreen(VizFrame(JsonObject(emptyMap()), listOf(
            container, node("button", "status", "status"), node("scene", "__scene"),
        )))
        assertEquals(container, layout.groups.getValue(VizControlGroup.Actions).single())
        assertEquals("status", layout.groups.getValue(VizControlGroup.Information).single().values.string("id"))
        assertEquals("__scene", layout.scenes.single().values.string("id"))
    }

    @Test fun boundsClampToTinyLandscapeAndKeyboardWithoutChangingPreferences() {
        val state = VizPanelState()
        val normal = panelBounds(state, 400f, 800f)
        assertEquals(280f, normal.width)
        assertEquals(220f, normal.height)
        state.width = 500f; state.height = 400f
        for ((width, height) in listOf(800f to 250f, 320f to 100f, 80f to 30f, 0f to 0f)) {
            val b = panelBounds(state, width, height)
            assertTrue(b.x >= 0 && b.y >= 0)
            assertTrue(b.x + b.width <= width && b.y + b.height <= height)
        }
        assertEquals(500f, state.width)
        assertEquals(400f, state.height)
        state.expanded = false
        assertEquals(48f, panelBounds(state, 400f, 800f).height)
        state.expanded = true
        assertEquals(400f, panelBounds(state, 800f, 800f).height)
    }

    @Test fun transferVersionAndPanelAreSessionScopedAndClearedWithSource() {
        val presentation = VizPresentationState()
        presentation.bindSession("a", "message")
        val panel = presentation.panel("a")
        panel.width = 300f
        presentation.beginTransfer("a")
        assertEquals(1L, presentation.transferVersion("a"))
        assertEquals(0L, presentation.transferVersion("b"))
        assertSame(panel, presentation.panel("a"))
        presentation.retainMessages(emptySet())
        assertNotSame(panel, presentation.panel("a"))
    }
}
