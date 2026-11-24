package me.rerere.rikkahub.phoneuse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhoneUseManagerTest {
    @Test
    fun stopAlsoInvalidatesAlreadyQueuedOperations() = runTest {
        val manager = PhoneUseManager()
        var secondRan = false
        val first = launch { manager.execute("first", "wait", {}) { awaitCancellation() } }
        runCurrent()
        val second = launch { manager.execute("second", "tap", {}) { secondRan = true } }
        runCurrent()
        manager.stop()
        first.join()
        second.join()
        assertFalse(secondRan)
        assertNull(manager.status.value)
    }

    @Test
    fun callsAcrossSessionsNeverOverlapAndStopRestoresState() = runTest {
        val manager = PhoneUseManager()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch {
            manager.execute("first", "tap", {}) {
                order += "first"
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val second = launch {
            manager.execute("second", "screenshot", {}) {
                order += "second"
                manager.setActing(true)
                awaitCancellation()
            }
        }
        runCurrent()
        assertEquals(listOf("first"), order)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), order)
        manager.stop()
        second.join()
        assertFalse(manager.acting.value)
        assertNull(manager.status.value)
    }

    @Test
    fun finishingAnotherConversationDoesNotHideActiveControls() = runTest {
        val manager = PhoneUseManager()
        var stopped = false
        manager.execute("active", "observe", { stopped = true }) { Unit }
        manager.finish("other")
        assertNotNull(manager.status.value)
        manager.stop()
        assertTrue(stopped)
        assertNull(manager.status.value)
    }
}
