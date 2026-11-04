package me.rerere.rikkahub.ui.components.ai

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CameraAttachmentTransitionTest {
    private val source = Rect(12f, 340f, 348f, 788f)

    @Test
    fun `photo starts at the measured preview and finishes at the real thumbnail`() {
        val target = Rect(22f, 614f, 110f, 702f)
        assertEquals(source, cameraAttachmentBounds(source, target, 0f))
        assertEquals(target, cameraAttachmentBounds(source, target, 1f))
        val middle = cameraAttachmentBounds(source, target, 0.5f)
        assertEquals(Rect(17f, 477f, 229f, 745f), middle)
        assertTrue(middle.width < source.width)
        assertTrue(middle.height < source.height)
    }

    @Test
    fun `keyboard open and closed use different measured landing positions`() {
        val closed = Rect(22f, 614f, 110f, 702f)
        val open = Rect(22f, 314f, 110f, 402f)
        assertEquals(closed, cameraAttachmentBounds(source, closed, 1f))
        assertEquals(open, cameraAttachmentBounds(source, open, 1f))
        assertEquals(150f,
            cameraAttachmentBounds(source, closed, 0.5f).top -
                cameraAttachmentBounds(source, open, 0.5f).top, 0.001f)
    }

    @Test
    fun `tablet drawer offsets and split screen are not treated as phone coordinates`() {
        val tabletSource = Rect(650f, 220f, 1266f, 682f)
        for (target in listOf(Rect(440f, 590f, 528f, 678f), Rect(722f, 290f, 810f, 378f))) {
            assertEquals(tabletSource, cameraAttachmentBounds(tabletSource, target, 0f))
            assertEquals(target, cameraAttachmentBounds(tabletSource, target, 1f))
        }
    }

    @Test
    fun `window centered camera can land in the offset tablet chat pane`() {
        val centeredPreview = Rect(312f, 326f, 928f, 788f)
        val thumbnailInChatPane = Rect(1010f, 614f, 1098f, 702f)
        assertEquals(620f, centeredPreview.center.x, 0.001f)
        assertEquals(centeredPreview, cameraAttachmentBounds(centeredPreview, thumbnailInChatPane, 0f))
        assertEquals(thumbnailInChatPane, cameraAttachmentBounds(centeredPreview, thumbnailInChatPane, 1f))
        assertTrue(cameraAttachmentBounds(centeredPreview, thumbnailInChatPane, 0.5f).center.x > 620f)
    }

    @Test
    fun `progress cannot overshoot the preview or thumbnail`() {
        val target = Rect(22f, 614f, 110f, 702f)
        assertEquals(source, cameraAttachmentBounds(source, target, -1f))
        assertEquals(target, cameraAttachmentBounds(source, target, 2f))
    }

    @Test
    fun `only the current captured photo supplies a valid landing rectangle`() {
        val state = CameraAttachmentTransitionState()
        state.prepare("new", source, 3)
        state.updateTarget("old", Rect(1f, 2f, 89f, 90f))
        assertNull(state.target)
        state.updateTarget("new", Rect.Zero)
        assertNull(state.target)
        state.updateTarget("new", source)
        assertEquals(source, state.target)
    }

    @Test
    fun `an old animation finishing cannot clear a newer photo`() {
        val state = CameraAttachmentTransitionState()
        state.prepare("first", source, 3)
        val first = state.photo!!
        state.prepare("second", source, 3)
        state.finish(first)
        assertEquals("second", state.photo?.url)
        state.finish(state.photo!!)
        assertNull(state.photo)
        assertNull(state.target)
        assertFalse(state.targetVisible)
    }

    @Test
    fun `another capture clears the previous scroll and layout readiness`() {
        val state = CameraAttachmentTransitionState()
        state.prepare("first", source, 3)
        state.updateTarget("first", source)
        state.targetVisible = true
        state.thumbnailLoaded("first")
        state.prepare("second", source, 4)
        assertNull(state.target)
        assertFalse(state.targetVisible)
        assertFalse(state.thumbnailReady)
        assertEquals(4L, state.photo?.revision)
    }

    @Test
    fun `only the arriving thumbnail signals a seamless handoff`() {
        val state = CameraAttachmentTransitionState()
        state.prepare("new", source, 3)
        state.thumbnailLoaded("old")
        assertFalse(state.thumbnailReady)
        state.thumbnailLoaded("new")
        assertTrue(state.thumbnailReady)
        state.finish(state.photo!!)
        assertFalse(state.thumbnailReady)
    }
}
