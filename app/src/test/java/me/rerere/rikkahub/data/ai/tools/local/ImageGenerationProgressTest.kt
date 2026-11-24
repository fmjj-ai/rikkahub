package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ImageGenerationProgressTest {
    @Test
    fun `parallel image previews do not overwrite each other and are cleaned individually`() {
        val pool = Executors.newFixedThreadPool(4)
        val ids = List(64) { "test-preview-$it" }
        try {
            ids.forEach { id -> pool.submit { ImageGenerationProgress.update(id, "file:///$id.png") } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            ids.forEach { id -> assertEquals("file:///$id.png", ImageGenerationProgress.previews.value[id]) }
            ImageGenerationProgress.clear(ids.first())
            assertFalse(ImageGenerationProgress.previews.value.containsKey(ids.first()))
            assertEquals("file:///${ids.last()}.png", ImageGenerationProgress.previews.value[ids.last()])
        } finally {
            pool.shutdownNow()
            ids.forEach(ImageGenerationProgress::clear)
        }
    }

    @Test
    fun `image file names are safe and unique even at the same millisecond`() {
        val first = newGeneratedImageFileName(123)
        val second = newGeneratedImageFileName(123)
        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("123_[0-9a-f-]+\\.png")))
        assertFalse(first.contains('/'))
        assertFalse(first.contains('\\'))
    }
}
