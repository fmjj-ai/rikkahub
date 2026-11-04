package me.rerere.rikkahub.ui.hooks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class DraftImageImporterTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(15)

    @Test
    fun `arrival is reported after acceptance but before the source is released`() = runBlocking {
        val state = ChatInputState()
        val events = mutableListOf<String>()
        val importer = DraftImageImporter(state, this, { "file:///saved.jpg" }, { fail(it) }, { fail(it) })
        importer.add(listOf("camera.jpg"), onImported = { source, url ->
            assertEquals("camera.jpg", source)
            assertEquals("file:///saved.jpg", url)
            assertTrue(state.messageContent.contains(UIMessagePart.Image(url!!)))
            events += "accepted"
        }) { events += "released" }
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(listOf("accepted", "released"), events)
    }

    @Test
    fun `failed and invalidated arrivals report null exactly once and still release their sources`() = runBlocking {
        for (invalidate in listOf(false, true)) {
            val state = ChatInputState()
            val reported = mutableListOf<String?>()
            val released = mutableListOf<String>()
            val importer = DraftImageImporter(state, this, {
                if (invalidate) { state.clearInput(); "file:///late.jpg" } else null
            }, {}, {})
            importer.add(listOf("camera.jpg"), onImported = { _, url -> reported += url }) { released += it }
            coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(listOf<String?>(null), reported)
            assertEquals(listOf("camera.jpg"), released)
            assertTrue(state.messageContent.isEmpty())
        }
    }

    @Test
    fun `camera source is released only after copy and even when draft changes`() = runBlocking {
        val state = ChatInputState()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val cleaned = mutableListOf<String>()
        val importer = DraftImageImporter(state, this, {
            entered.complete(Unit); finish.await(); "file:///copied.jpg"
        }, { cleaned += it }, { fail(it) })
        importer.add(listOf("camera.jpg")) { cleaned += it }
        entered.await()
        assertTrue(cleaned.isEmpty())
        state.clearInput()
        finish.complete(Unit)
        coroutineContext[Job]!!.children.toList().joinAll()
        assertTrue(state.messageContent.isEmpty())
        assertEquals(listOf("file:///copied.jpg", "camera.jpg"), cleaned)
    }

    @Test
    fun `camera source is also released on import failure`() = runBlocking {
        val state = ChatInputState()
        val cleaned = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val importer = DraftImageImporter(state, this, { null }, { fail(it) }, { errors += it })
        importer.add(listOf("camera.jpg")) { cleaned += it }
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(listOf("camera.jpg"), cleaned)
        assertEquals(1, errors.size)
        assertEquals(0, state.pendingGalleryImageCount)
    }

    @Test
    fun `multi selection copies once in order and never discards accepted files`() = runBlocking {
        val state = ChatInputState()
        val copies = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val importer = DraftImageImporter(state, this, { copies += it; "file:///$it.png" }, { deleted += it }, { fail(it) })
        importer.add(listOf("one", "two", "one", "three"))
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(listOf("one", "two", "three"), copies)
        assertEquals(copies.map { UIMessagePart.Image("file:///$it.png") }, state.messageContent)
        assertTrue(deleted.isEmpty())
        assertEquals(0, state.pendingGalleryImageCount)
    }

    @Test
    fun `unselecting a copy in progress cleans its file and does not add it`() = runBlocking {
        val state = ChatInputState()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        val importer = DraftImageImporter(state, this, {
            entered.complete(Unit); finish.await(); "file:///late.png"
        }, { deleted += it }, { fail(it) })
        importer.add(listOf("one"))
        entered.await()
        importer.toggle("one")
        finish.complete(Unit)
        coroutineContext[Job]!!.children.toList().joinAll()
        assertTrue(state.messageContent.isEmpty())
        assertTrue(state.selectedGallerySources.isEmpty())
        assertEquals(listOf("file:///late.png"), deleted)
    }

    @Test
    fun `clearing the draft while copying does not put old images into the new message`() = runBlocking {
        val state = ChatInputState()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        val importer = DraftImageImporter(state, this, {
            entered.complete(Unit); finish.await(); "file:///late.png"
        }, { deleted += it }, { fail(it) })
        importer.add(listOf("one"))
        entered.await()
        state.clearInput()
        finish.complete(Unit)
        coroutineContext[Job]!!.children.toList().joinAll()
        assertTrue(state.messageContent.isEmpty())
        assertEquals(listOf("file:///late.png"), deleted)
    }

    @Test
    fun `a failed file does not block later files or leave a selected marker`() = runBlocking {
        val state = ChatInputState()
        val errors = mutableListOf<String>()
        val importer = DraftImageImporter(state, this, { if (it == "bad") null else "file:///$it.png" }, {}, { errors += it })
        importer.add(listOf("bad", "good"))
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(1, errors.size)
        assertEquals(setOf("good"), state.selectedGallerySources)
        assertEquals(listOf(UIMessagePart.Image("file:///good.png")), state.messageContent)
        assertEquals(0, state.pendingGalleryImageCount)
    }

    @Test
    fun `leaving the owning viewmodel cleans an in flight file before terminating`() = runBlocking {
        val state = ChatInputState()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val owner = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + owner)
        val deleted = mutableListOf<String>()
        val importer = DraftImageImporter(state, scope, {
            entered.complete(Unit); finish.await(); "file:///cancelled.png"
        }, { deleted += it }, { fail(it) })
        importer.add(listOf("one"))
        entered.await()
        owner.cancel()
        finish.complete(Unit)
        owner.join()
        assertTrue(state.messageContent.isEmpty())
        assertEquals(listOf("file:///cancelled.png"), deleted)
        assertEquals(0, state.pendingGalleryImageCount)
    }

    @Test
    fun `deselecting an accepted image deletes only that draft file`() = runBlocking {
        val state = ChatInputState()
        val deleted = mutableListOf<String>()
        val importer = DraftImageImporter(state, this, { "file:///$it.png" }, { deleted += it }, { fail(it) })
        importer.add(listOf("one", "two"))
        coroutineContext[Job]!!.children.toList().joinAll()
        importer.toggle("one")
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(listOf("file:///one.png"), deleted)
        assertEquals(listOf(UIMessagePart.Image("file:///two.png")), state.messageContent)
    }
}
