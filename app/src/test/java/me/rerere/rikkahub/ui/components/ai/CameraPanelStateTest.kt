package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.*
import org.junit.Test

class CameraPanelStateTest {
    @Test
    fun `return keeps the preview mounted until the reverse animation finishes`() {
        val panel = CameraPanelState()
        panel.open()
        assertTrue(panel.acceptsCapture)
        assertTrue(panel.requestClose())
        assertTrue(panel.showing)
        assertTrue(panel.returningToAttachments)
        assertFalse(panel.acceptsCapture)
        assertFalse(panel.requestClose())
        assertEquals(CameraCloseAction.RETURN_TO_ATTACHMENTS, panel.finishClose())
        assertFalse(panel.showing)
        assertNull(panel.finishClose())
    }

    @Test
    fun `background dismissal is kept separate from returning to attachments`() {
        val panel = CameraPanelState()
        panel.open()
        panel.requestClose()
        panel.requestClose(dismissPicker = true)
        assertFalse(panel.returningToAttachments)
        assertEquals(CameraCloseAction.DISMISS_PICKER, panel.finishClose())
    }

    @Test
    fun `stale close completion cannot dismiss a newly opened camera`() {
        val panel = CameraPanelState()
        panel.open()
        panel.requestClose()
        panel.open()
        assertNull(panel.finishClose())
        assertTrue(panel.showing)
        assertTrue(panel.acceptsCapture)
        panel.dismiss()
        assertFalse(panel.showing)
        assertNull(panel.finishClose())
    }

    @Test
    fun `loading text is only used for a real slow startup after expansion`() {
        assertFalse(showCameraLoadingHint(expanded = false, streaming = false, hasError = false))
        assertFalse(showCameraLoadingHint(expanded = true, streaming = true, hasError = false))
        assertFalse(showCameraLoadingHint(expanded = true, streaming = false, hasError = true))
        assertTrue(showCameraLoadingHint(expanded = true, streaming = false, hasError = false))
    }

    @Test
    fun `attachments and camera use complementary alpha without rebuilding the sheet`() {
        for (progress in listOf(-1f, 0f, 0.05f, 0.125f, 0.25f, 0.5f, 1f, 2f)) {
            assertEquals(1f, cameraAttachmentsAlpha(progress) + cameraClosingAlpha(progress), 0.001f)
        }
        assertEquals(0f, cameraAttachmentsAlpha(1f), 0f)
        assertEquals(0f, cameraAttachmentsAlpha(0.25f), 0f)
        assertEquals(0.5f, cameraAttachmentsAlpha(0.125f), 0f)
        assertEquals(1f, cameraAttachmentsAlpha(0f), 0f)
        val panel = CameraPanelState()
        panel.open()
        panel.updateProgress(0.6f)
        assertEquals(0.6f, panel.progress, 0f)
        panel.dismiss()
        assertEquals(0f, panel.progress, 0f)
    }

    @Test
    fun `return starts from the displayed frame instead of resetting progress`() {
        val panel = CameraPanelState()
        panel.open()
        panel.updateProgress(0.125f)
        val before = cameraAttachmentsAlpha(panel.progress)
        panel.requestClose()
        assertEquals(0.125f, panel.progress, 0f)
        assertEquals(before, cameraAttachmentsAlpha(panel.progress), 0f)
    }

    @Test
    fun `closing completion leaves attachments fully visible in the same frame`() {
        val panel = CameraPanelState()
        panel.open()
        panel.updateProgress(1f)
        panel.requestClose()
        panel.updateProgress(0f)
        assertEquals(1f, cameraAttachmentsAlpha(panel.progress), 0f)
        assertEquals(CameraCloseAction.RETURN_TO_ATTACHMENTS, panel.finishClose())
        assertEquals(1f, cameraAttachmentsAlpha(panel.progress), 0f)
        assertFalse(panel.showing)
    }
}
