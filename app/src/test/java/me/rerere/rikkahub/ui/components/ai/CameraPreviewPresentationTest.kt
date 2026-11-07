package me.rerere.rikkahub.ui.components.ai

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewPresentationTest {
    @Test
    fun `transition frames preserve aspect ratio and cap phone and tablet memory use`() {
        assertEquals(IntSize(1024, 576), cameraPreviewFrameSize(1920, 1080))
        assertEquals(IntSize(576, 1024), cameraPreviewFrameSize(1080, 1920))
        assertEquals(IntSize(1024, 768), cameraPreviewFrameSize(4096, 3072))
        assertEquals(IntSize(1024, 1024), cameraPreviewFrameSize(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `small frames are not upscaled and thin frames cannot become zero sized`() {
        assertEquals(IntSize(320, 240), cameraPreviewFrameSize(320, 240))
        assertEquals(IntSize(1, 1024), cameraPreviewFrameSize(1, 12000))
    }

    @Test
    fun `unmeasured frames cannot allocate a snapshot`() {
        assertEquals(IntSize.Zero, cameraPreviewFrameSize(0, 1080))
        assertEquals(IntSize.Zero, cameraPreviewFrameSize(1080, -1))
    }

    @Test
    fun `opening never draws the native preview before reaching the final bounds`() {
        for (ready in listOf(false, true)) {
            assertFalse(useStationaryCameraPreview(ready, atRest = false, visible = true, closingFrameReady = false))
        }
    }

    @Test
    fun `a fast first frame shows live immediately when expansion completes`() {
        assertTrue(useStationaryCameraPreview(true, atRest = true, visible = true, closingFrameReady = false))
    }

    @Test
    fun `a slow startup cannot expose an uninitialized native view`() {
        assertFalse(useStationaryCameraPreview(false, atRest = true, visible = true, closingFrameReady = false))
    }

    @Test
    fun `return keeps live preview until its replacement frame has been prepared`() {
        assertTrue(useStationaryCameraPreview(true, atRest = true, visible = false, closingFrameReady = false))
        assertFalse(useStationaryCameraPreview(true, atRest = true, visible = false, closingFrameReady = true))
    }

    @Test
    fun `neither quick return nor a partly shrunken frame can draw the native preview`() {
        for (ready in listOf(false, true)) {
            assertFalse(useStationaryCameraPreview(true, atRest = false, visible = false, closingFrameReady = ready))
        }
    }
}
