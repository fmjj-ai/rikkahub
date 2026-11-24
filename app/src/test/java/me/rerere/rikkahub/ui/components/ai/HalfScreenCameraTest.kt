package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.dp

class HalfScreenCameraTest {
    @Test
    fun `portrait preview uses three by four and landscape stays within the screen`() {
        assertEquals(448f, cameraPreviewHeight(360.dp, 800.dp).value, 0.001f)
        assertEquals(216f, cameraPreviewHeight(800.dp, 360.dp).value, 0.001f)
        assertEquals(360f, cameraPreviewHeight(320.dp, 600.dp).value, 0.001f)
        assertTrue(cameraPreviewHeight(0.dp, 0.dp).value > 0f)
    }

    @Test
    fun `capture orientation follows the device even with a locked display`() {
        for (orientation in listOf(0, 44, 315, 359)) assertEquals(0, cameraCaptureRotation(orientation))
        for (orientation in listOf(45, 134)) assertEquals(3, cameraCaptureRotation(orientation))
        for (orientation in listOf(135, 224)) assertEquals(2, cameraCaptureRotation(orientation))
        for (orientation in listOf(225, 314)) assertEquals(1, cameraCaptureRotation(orientation))
        assertNull(cameraCaptureRotation(-1))
        assertNull(cameraCaptureRotation(360))
    }

    @Test
    fun `landscape tablet uses the actual sheet width instead of the full display`() {
        assertEquals(462f, cameraPreviewHeight(640.dp, 800.dp, landscape = true).value, 0.001f)
        assertEquals(360f, cameraPreviewHeight(640.dp, 600.dp, landscape = true).value, 0.001f)
    }

    @Test
    fun `portrait tablet and split screen respect their measured panel width`() {
        assertEquals(768f, cameraPreviewHeight(640.dp, 1280.dp).value, 0.001f)
        assertEquals(448f, cameraPreviewHeight(360.dp, 1000.dp).value, 0.001f)
        assertEquals(216f, cameraPreviewHeight(640.dp, 360.dp, landscape = true).value, 0.001f)
    }
}
