package me.rerere.rikkahub.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAttachmentPolicyTest {
    @Test
    fun `camera requires editing even when gallery editing is skipped`() {
        assertTrue(shouldEditImageAttachment(ImageAttachmentSource.CAMERA, 1, true))
        assertTrue(shouldEditImageAttachment(ImageAttachmentSource.CAMERA, 1, false))
    }

    @Test
    fun `single gallery image never forces editing regardless of the old skip setting`() {
        assertFalse(shouldEditImageAttachment(ImageAttachmentSource.GALLERY, 1, false))
        assertFalse(shouldEditImageAttachment(ImageAttachmentSource.GALLERY, 1, true))
    }

    @Test
    fun `multiple gallery images do not start a forced editing queue`() {
        assertFalse(shouldEditImageAttachment(ImageAttachmentSource.GALLERY, 3, false))
        assertFalse(shouldEditImageAttachment(ImageAttachmentSource.GALLERY, 3, true))
    }

    @Test
    fun `cancelled or invalid selection does not open the editor`() {
        ImageAttachmentSource.entries.forEach { source ->
            assertFalse(shouldEditImageAttachment(source, 0, false))
            assertFalse(shouldEditImageAttachment(source, -1, false))
        }
    }
}
