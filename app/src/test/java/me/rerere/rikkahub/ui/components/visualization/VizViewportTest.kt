package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class VizViewportTest {
    @Test
    fun defaultViewFitsWithoutDistortionAndDoesNotConsumePagePan() {
        val state = VizViewportState()
        val size = IntSize(400, 800)
        state.resize(800f, 400f, size)
        assertEquals(0.5f, state.scale(800f, 400f, size), 0.001f)
        assertEquals(Offset(0f, 300f), state.offset(800f, 400f, size))
        assertEquals(Offset.Zero, state.transform(800f, 400f, size, Offset.Zero, Offset(0f, 60f), 1f))
    }

    @Test
    fun zoomStaysWithinBoundsAndPanReturnsOnlyConsumedDistance() {
        val state = VizViewportState()
        val size = IntSize(400, 400)
        state.resize(400f, 400f, size)
        state.zoomBy(2f, 400f, 400f, size)
        val consumed = state.transform(400f, 400f, size, Offset(200f, 200f), Offset(1000f, 0f), 1f)
        assertEquals(200f, consumed.x, 0.001f)
        assertEquals(100f, state.centerX, 0.001f)
        state.zoomBy(100f, 400f, 400f, size)
        assertEquals(8f, state.zoom, 0.001f)
        state.zoomBy(0.0001f, 400f, 400f, size)
        assertEquals(0.5f, state.zoom, 0.001f)
        state.reset()
        assertFalse(state.adjusted)
        assertEquals(1f, state.zoom, 0.001f)
    }

    @Test
    fun repeatedWindowResizingPreservesShortEdgeWorldSpan() {
        val state = VizViewportState()
        val portrait = IntSize(400, 800)
        val landscape = IntSize(800, 400)
        state.resize(800f, 400f, portrait)
        state.zoomBy(4f, 800f, 400f, portrait)
        val span = 400f / state.scale(800f, 400f, portrait)
        repeat(10) {
            state.resize(800f, 400f, landscape)
            assertEquals(span, 400f / state.scale(800f, 400f, landscape), 0.001f)
            state.resize(800f, 400f, portrait)
            assertEquals(span, 400f / state.scale(800f, 400f, portrait), 0.001f)
        }
        assertEquals(400f, state.centerX, 0.001f)
        assertEquals(200f, state.centerY, 0.001f)
    }
}
