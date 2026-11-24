package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    @Test
    fun concurrentInitializationLoadsOnlyOnceAndKeepsActiveState() = runBlocking {
        val session = newSession(this)
        val loaded = savedConversation(session)
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { calls++; finish.await(); loaded }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { calls++; error("不应重复读取数据库") }
        }
        try {
            assertEquals(1, calls)
            assertTrue(session.isInUse)
        } finally {
            finish.complete(Unit)
        }
        assertSame(loaded, first.await())
        assertSame(loaded, second.await())
        assertFalse(session.isInUse)
        session.update { it.copy(title = "active title") }
        assertEquals("active title", session.initialize { error("不应回读旧快照") }.title)
    }

    @Test
    fun editsDuringLoadingAreAppliedToCompleteHistory() = runBlocking {
        val session = newSession(this)
        val loaded = savedConversation(session)
        val finish = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { finish.await(); loaded }
        }
        val assistantId = Uuid.random()
        session.update { it.copy(title = "typed title") }
        session.update { it.copy(assistantId = assistantId, customSystemPrompt = "typed prompt") }
        finish.complete(Unit)
        val current = pending.await()

        assertEquals("typed title", current.title)
        assertEquals(assistantId, current.assistantId)
        assertEquals("typed prompt", current.customSystemPrompt)
        assertSame(loaded.messageNodes, current.messageNodes)
    }

    @Test
    fun clearingPlaceholderNullStillClearsLoadedValue() = runBlocking {
        val session = newSession(this)
        session.update { it.copy(customSystemPrompt = null, workspaceCwd = null) }
        val loaded = savedConversation(session).copy(customSystemPrompt = "saved prompt", workspaceCwd = "/saved")
        val current = session.initialize { loaded }

        assertNull(current.customSystemPrompt)
        assertNull(current.workspaceCwd)
        assertSame(loaded.messageNodes, current.messageNodes)
    }

    @Test
    fun failedLoadingCanRetryWithoutDroppingPendingEdits() = runBlocking {
        val session = newSession(this)
        session.update { it.copy(title = "pending edit") }
        val failed = runCatching { session.initialize { throw IOException("database unavailable") } }
        assertTrue(failed.isFailure)
        assertEquals("database unavailable", failed.exceptionOrNull()?.message)

        val loaded = savedConversation(session)
        val current = session.initialize { loaded }
        assertEquals("pending edit", current.title)
        assertSame(loaded.messageNodes, current.messageNodes)
    }

    @Test
    fun cancelledLoadingCanRetryWithoutPublishingAnIncompleteState() = runBlocking {
        val session = newSession(this)
        session.update { it.copy(title = "pending edit") }
        val entered = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        pending.cancelAndJoin()
        assertFalse(session.isInUse)
        val loaded = savedConversation(session)
        val current = session.initialize { loaded }
        assertEquals("pending edit", current.title)
        assertSame(loaded.messageNodes, current.messageNodes)
    }

    @Test
    fun explicitEmptyConversationDoesNotReceiveDefaultPresets() = runBlocking {
        val session = newSession(this)
        val explicit = Conversation.ofId(session.id, Uuid.random())
        session.replace(explicit)
        val current = session.initialize { error("显式新建不应追加默认预设或回读数据库") }
        assertSame(explicit, current)
        assertTrue(current.messageNodes.isEmpty())
    }

    @Test
    fun explicitReplacementWinsOverLateResultOrFailure() = runBlocking {
        for (fail in listOf(false, true)) {
            val session = newSession(this)
            val loaded = savedConversation(session)
            val finish = CompletableDeferred<Unit>()
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                session.initialize {
                    finish.await()
                    if (fail) throw IOException("late database failure")
                    loaded
                }
            }
            val explicit = Conversation.ofId(session.id, Uuid.random()).copy(title = "explicit")
            session.replace(explicit)
            try {
                val current = withTimeout(2_000) {
                    session.initialize { error("完整态不应再等旧加载") }
                }
                assertSame(explicit, current)
            } finally {
                finish.complete(Unit)
            }
            assertSame(explicit, pending.await())
            assertSame(explicit, session.state.value)
        }
    }

    @Test
    fun concurrentTransformsKeepAllIndependentChanges() = runBlocking {
        val session = newSession(this)
        session.replace(savedConversation(session))
        List(100) { index ->
            launch(Dispatchers.Default) {
                session.update { it.copy(chatSuggestions = it.chatSuggestions + index.toString()) }
            }
        }.joinAll()
        assertEquals(100, session.state.value.chatSuggestions.size)
        assertEquals(100, session.state.value.chatSuggestions.toSet().size)
    }

    @Test
    fun referenceRemainsHeldAfterLoadingUntilSuspendingWorkFinishes() = runBlocking {
        val session = newSession(this)
        val writing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            session.withRefSuspend {
                session.initialize { savedConversation(session) }
                writing.complete(Unit)
                finish.await()
            }
        }
        try {
            writing.await()
            assertTrue("加载结束后的写入阶段仍应持有引用", session.isInUse)
            finish.complete(Unit)
            operation.await()
            assertFalse(session.isInUse)
        } finally {
            finish.complete(Unit)
            operation.cancelAndJoin()
            session.cleanup()
        }
    }

    @Test
    fun cancellingReferencedOperationReleasesReference() = runBlocking {
        val session = newSession(this)
        val writing = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            session.withRefSuspend {
                session.initialize { savedConversation(session) }
                writing.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            writing.await()
            assertTrue(session.isInUse)
            operation.cancelAndJoin()
            assertFalse(session.isInUse)
        } finally {
            operation.cancelAndJoin()
            session.cleanup()
        }
    }

    private fun newSession(scope: CoroutineScope): ConversationSession {
        val id = Uuid.random()
        return ConversationSession(id, Conversation.ofId(id, Uuid.random()), scope, log = {}) {}
    }

    private fun savedConversation(session: ConversationSession): Conversation =
        Conversation.ofId(session.id, Uuid.random()).copy(title = "saved").updateCurrentMessages(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("saved history")))),
        )
}
