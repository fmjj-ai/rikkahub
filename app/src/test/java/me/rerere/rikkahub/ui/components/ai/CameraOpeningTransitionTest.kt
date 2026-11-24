package me.rerere.rikkahub.ui.components.ai

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOpeningTransitionTest {
    @Test
    fun `reverse shrinks to the same tablet button without introducing a drawer offset`() {
        val source = Rect(410f, 450f, 510f, 530f)
        val target = Rect(280f, 200f, 920f, 700f)
        val frames = listOf(1f, 0.75f, 0.5f, 0.25f, 0f).map { cameraOpeningBounds(source, target, it) }
        assertEquals(target, frames.first())
        assertEquals(source, frames.last())
        assertTrue(frames.zipWithNext().all { (before, after) -> before.width > after.width })
    }

    @Test
    fun `quick return duration follows the current animation position`() {
        assertEquals(360, cameraTransitionDuration(0f, visible = true))
        assertEquals(300, cameraTransitionDuration(1f, visible = false))
        assertEquals(150, cameraTransitionDuration(0.5f, visible = false))
        assertEquals(75, cameraTransitionDuration(0.25f, visible = false))
        assertEquals(0, cameraTransitionDuration(0f, visible = false))
        assertEquals(0, cameraTransitionDuration(1f, visible = true))
    }

    @Test
    fun `camera expands from the tapped button to the measured panel`() {
        val source = Rect(16f, 650f, 116f, 730f)
        val target = Rect(0f, 300f, 400f, 800f)
        assertEquals(source, cameraOpeningBounds(source, target, 0f))
        assertEquals(target, cameraOpeningBounds(source, target, 1f))
        assertEquals(Rect(8f, 475f, 258f, 765f), cameraOpeningBounds(source, target, 0.5f))
        assertEquals(source, cameraOpeningBounds(source, target, -1f))
        assertEquals(target, cameraOpeningBounds(source, target, 2f))
    }

    @Test
    fun `tablet opening stays in window coordinates without chat pane offsets`() {
        val source = Rect(410f, 450f, 510f, 530f)
        val target = Rect(280f, 200f, 920f, 700f)
        val middle = cameraOpeningBounds(source, target, 0.5f)
        assertEquals(Rect(345f, 325f, 715f, 615f), middle)
        assertEquals(target, cameraOpeningBounds(source, target, 1f))
    }

    @Test
    fun `missing or invalid source opens from the bottom center of the panel`() {
        val target = Rect(280f, 200f, 920f, 700f)
        for (source in listOf(Rect.Zero, Rect(Float.NaN, 0f, 100f, 100f))) {
            val start = cameraOpeningBounds(source, target, 0f)
            assertEquals(target.center.x, start.center.x, 0.01f)
            assertEquals(target.bottom, start.bottom, 0.01f)
            assertTrue(start.width > 0f && start.width < target.width)
            assertTrue(start.height > 0f && start.height < target.height)
            assertEquals(target, cameraOpeningBounds(source, target, 1f))
        }
    }
}
