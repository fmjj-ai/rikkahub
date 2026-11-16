package me.rerere.rikkahub.ui.components.message

import kotlinx.coroutines.*
import me.rerere.rikkahub.ui.components.richtext.*
import org.junit.Assert.*
import org.junit.Test

class MessageMarkdownStateStoreTest {
    private fun key(id: String = "message", visual: Boolean = true) =
        MarkdownStateKey("conversation", id, 0, visual, emptyList())

    private suspend fun await(check: () -> Boolean) = withTimeout(5000) {
        while (!check()) delay(1)
    }

    @Test fun remountReturnsSameCompleteSnapshotWithoutAnEmptyFrame() = runBlocking {
        val store = MessageMarkdownStateStore(this)
        try {
            val input = MarkdownInput("**正文**", emptyList())
            val entry = store.entry(key(), input) { it }
            store.acquire(entry)
            entry.request(input)
            await { entry.snapshot.prepared != null }
            val snapshot = entry.snapshot
            store.release(entry)
            val remounted = store.entry(key(), input) { it }
            assertSame(entry, remounted)
            assertSame(snapshot, remounted.snapshot)
            assertEquals("**正文**", snapshot.source)
            assertEquals(input, remounted.completedInput)
        } finally { store.close() }
    }

    @Test fun firstFrameNeverLeaksCandidateJsonAndPrecedingPartsInvalidateQuota() = runBlocking {
        val store = MessageMarkdownStateStore(this)
        try {
            val source = "说明\n\n```rikka-viz\n{\"secret-protocol\":1}\n```"
            val input = MarkdownInput(source, emptyList())
            val entry = store.entry(key(), input) { it }
            store.acquire(entry)
            assertFalse(entry.snapshot.fallbackText.contains("secret-protocol"))
            assertTrue(entry.snapshot.fallbackText.isNotEmpty())
            entry.request(input)
            await { entry.snapshot.prepared != null }
            assertEquals(1, entry.snapshot.prepared!!.visualizationFences.size)
            entry.request(input.copy(precedingSources = List(4) { source }))
            await { entry.snapshot.prepared!!.overflowFences.size == 1 }
            assertTrue(entry.snapshot.prepared!!.visualizationFences.isEmpty())
            assertFalse(entry.snapshot.fallbackText.contains("secret-protocol"))
        } finally { store.close() }
    }

    @Test fun inactiveEntriesAreBoundedAndActiveSnapshotIsNeverEvicted() = runBlocking {
        val store = MessageMarkdownStateStore(this, maxInactive = 2, characterBudget = 200)
        try {
            val input = MarkdownInput("x".repeat(100), emptyList())
            val active = store.entry(key("active"), input) { it }
            store.acquire(active)
            repeat(20) {
                val entry = store.entry(key("old:$it"), input) { text -> text }
                store.acquire(entry)
                store.release(entry)
            }
            assertSame(active, store.entry(key("active"), input) { it })
            assertEquals(1, store.size)
            store.release(active)
            assertTrue(store.size <= 1)
            store.retainParts(emptySet(), emptySet())
            assertEquals(0, store.size)
        } finally { store.close() }
    }

    @Test fun serialPreparationConflatesPendingInputAndFailureKeepsSnapshot() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val store = MessageMarkdownStateStore(this, prepare = { input, _, _, _ ->
            calls += input.source
            if (input.source == "first") { entered.complete(Unit); gate.await() }
            if (input.source == "failure") throw IllegalArgumentException("expected")
            prepareMarkdownState(input.source)
        })
        try {
            val entry = store.entry(key(), MarkdownInput("first", emptyList())) { it }
            store.acquire(entry)
            entry.request(MarkdownInput("first", emptyList()))
            entered.await()
            entry.request(MarkdownInput("skipped", emptyList()))
            entry.request(MarkdownInput("last", emptyList()))
            assertNull(entry.completedInput)
            gate.complete(Unit)
            await { entry.snapshot.source == "last" }
            assertEquals(listOf("first", "last"), calls)
            assertEquals("last", entry.completedInput?.source)
            val prepared = entry.snapshot.prepared
            entry.request(MarkdownInput("failure", emptyList()))
            await { entry.snapshot.error != null }
            assertSame(prepared, entry.snapshot.prepared)
            assertEquals("last", entry.snapshot.source)
        } finally { store.close() }
    }

    @Test fun deletionCancelsInFlightResultAndNewBranchDoesNotShareState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val store = MessageMarkdownStateStore(this, prepare = { _, _, _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        })
        try {
            val input = MarkdownInput("old", emptyList())
            val entry = store.entry(key("branch:0"), input) { it }
            entry.request(input)
            entered.await()
            store.retainParts(emptySet(), emptySet())
            assertEquals(0, store.size)
            val next = store.entry(key("branch:1"), input.copy(source = "new")) { it }
            assertNotSame(entry, next)
            assertEquals("new", next.snapshot.fallbackText)
        } finally { store.close() }
    }

    @Test fun configurationChangeDiscardsOldInFlightPreparation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val store = MessageMarkdownStateStore(this, prepare = { input, _, visual, _ ->
            if (visual) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            prepareMarkdownState(input.source)
        })
        try {
            val input = MarkdownInput("正文", emptyList())
            val old = store.entry(key(), input) { it }
            store.acquire(old)
            old.request(input)
            entered.await()
            val changedKey = key(visual = false)
            val next = store.entry(changedKey, input) { it }
            store.acquire(next)
            store.retainConfiguration(changedKey)
            next.request(input)
            cancelled.await()
            await { next.completedInput == input }
            assertEquals(1, store.size)
            assertNull(old.completedInput)
            assertNotNull(next.snapshot.prepared)
        } finally { store.close() }
    }
}
