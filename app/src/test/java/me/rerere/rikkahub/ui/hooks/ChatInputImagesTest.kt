package me.rerere.rikkahub.ui.hooks

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInputImagesTest {
    @Test
    fun `duplicate source is selected only once and becomes selectable after removing attachment`() {
        val state = ChatInputState()
        val token = requireNotNull(state.beginGalleryImageImport("source"))
        assertNull(state.beginGalleryImageImport("source"))
        assertEquals(1, state.pendingGalleryImageCount)
        assertTrue(state.finishGalleryImageImport("source", token, "file:///one.png"))
        assertEquals(0, state.pendingGalleryImageCount)
        assertEquals(setOf("source"), state.selectedGallerySources)
        state.removeAttachment(state.messageContent.single())
        assertTrue(state.selectedGallerySources.isEmpty())
        assertNotNull(state.beginGalleryImageImport("source"))
    }

    @Test
    fun `deselecting during copy and reselecting rejects the old result`() {
        val state = ChatInputState()
        val old = requireNotNull(state.beginGalleryImageImport("source"))
        assertNull(state.deselectGalleryImage("source"))
        val current = requireNotNull(state.beginGalleryImageImport("source"))
        assertFalse(state.finishGalleryImageImport("source", old, "file:///old.png"))
        state.cancelGalleryImageImport("source", old)
        assertTrue(state.isGalleryImageImportPending("source", current))
        assertTrue(state.finishGalleryImageImport("source", current, "file:///new.png"))
        assertEquals(listOf(UIMessagePart.Image("file:///new.png")), state.messageContent)
    }

    @Test
    fun `clear or switch draft prevents late imports from joining a different message`() {
        val state = ChatInputState()
        val first = requireNotNull(state.beginGalleryImageImport("source"))
        state.clearInput()
        assertFalse(state.finishGalleryImageImport("source", first, "file:///old.png"))
        val second = requireNotNull(state.beginGalleryImageImport("source"))
        state.setContents(listOf(UIMessagePart.Text("another message")))
        assertFalse(state.finishGalleryImageImport("source", second, "file:///old.png"))
        assertTrue(state.messageContent.isEmpty())
    }

    @Test
    fun `cropping keeps original position selection and original historical file`() {
        val state = ChatInputState()
        val original = UIMessagePart.Image("file:///history.png")
        val second = UIMessagePart.Image("file:///second.png")
        state.setContents(listOf(UIMessagePart.Text("before"), original, UIMessagePart.Text("after"), second))
        state.editingMessage = Uuid.random()
        assertFalse(state.shouldDeleteFileOnRemove(original))
        assertTrue(state.replaceImage(original, "file:///crop.png"))
        assertEquals(listOf(UIMessagePart.Text("before"), UIMessagePart.Image("file:///crop.png"),
            UIMessagePart.Text("after"), second), state.getContents())
        assertTrue(state.shouldDeleteFileOnRemove(UIMessagePart.Image("file:///crop.png")))
        assertFalse(state.shouldDeleteFileOnRemove(original))
    }

    @Test
    fun `cropping selected gallery image updates the selection target rather than appending another image`() {
        val state = ChatInputState()
        val token = requireNotNull(state.beginGalleryImageImport("source"))
        state.finishGalleryImageImport("source", token, "file:///image.png")
        assertTrue(state.replaceImage(state.messageContent.single() as UIMessagePart.Image, "file:///crop.png"))
        assertEquals(1, state.messageContent.size)
        assertEquals(UIMessagePart.Image("file:///crop.png"), state.deselectGalleryImage("source"))
        assertTrue(state.messageContent.isEmpty())
    }

    @Test
    fun `removed image and changed draft cannot be resurrected by a late crop`() {
        val state = ChatInputState()
        val original = UIMessagePart.Image("file:///same.png")
        state.setContents(listOf(original))
        val revision = state.attachmentRevision
        state.removeAttachment(original)
        assertFalse(state.replaceImage(original, "file:///crop.png", revision))
        state.setContents(listOf(original))
        assertFalse(state.replaceImage(original, "file:///crop.png", revision))
        assertEquals(listOf(original), state.messageContent)
    }

    @Test
    fun `cancel failure cleanup does not clear an accepted selection`() {
        val state = ChatInputState()
        val token = requireNotNull(state.beginGalleryImageImport("source"))
        state.finishGalleryImageImport("source", token, "file:///image.png")
        state.cancelGalleryImageImport("source", token)
        assertEquals(setOf("source"), state.selectedGallerySources)
    }
}
