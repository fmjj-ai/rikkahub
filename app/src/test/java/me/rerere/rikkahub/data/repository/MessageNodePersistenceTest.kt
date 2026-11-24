package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.planFtsNodeMutations
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ConversationSession
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MessageNodePersistenceTest {
    private val conversationId = Uuid.random()

    private fun message(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun node(text: String) = MessageNode(id = Uuid.random(), messages = listOf(message(text)), selectIndex = 0)

    private fun stored(nodes: List<MessageNode>) = nodes.mapIndexed { index, node ->
        MessageNodeEntity(node.id.toString(), conversationId.toString(), index,
            JsonInstant.encodeToString(node.messages), node.selectIndex)
    }

    @Test
    fun unchangedContentAndFavoriteDoNotWriteNodesOrFts() {
        val node = node("中文、日本語、🙂")
        val plan = planMessageNodeWrites(conversationId.toString(), stored(listOf(node)), listOf(node.copy(isFavorite = true)))
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.positions.isEmpty())
        assertTrue(plan.deletedIds.isEmpty())
        assertTrue(plan.previousFtsNodes.isEmpty())
        assertTrue(plan.currentFtsNodes.isEmpty())
    }

    @Test
    fun selectionAndPositionOnlyWriteSmallColumns() {
        val first = node("selected").let { it.copy(messages = it.messages + message("hidden")) }
        val second = node("second")
        val current = listOf(second, first.copy(selectIndex = 1))
        val plan = planMessageNodeWrites(conversationId.toString(), stored(listOf(first, second)), current)
        assertEquals(2, plan.positions.size)
        assertEquals(1, plan.positions.first { it.id == first.id.toString() }.selectIndex)
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.currentFtsNodes.isEmpty())
    }

    @Test
    fun longHistoryWritesOnlyTheChangedNode() {
        val original = List(1_000) { node("message $it") }
        val tail = original.last().let { it.copy(messages = listOf(it.messages.single().copy(parts = listOf(UIMessagePart.Text("changed"))))) }
        val plan = planMessageNodeWrites(conversationId.toString(), stored(original), original.dropLast(1) + tail)
        assertEquals(listOf(tail.id.toString()), plan.updates.map { it.id })
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.positions.isEmpty())
        assertEquals(1, plan.previousFtsNodes.size)
        assertSame(tail, plan.currentFtsNodes.single())
        assertEquals(listOf(tail.id.toString()), planFtsNodeMutations(plan.previousFtsNodes, plan.currentFtsNodes).map { it.nodeId })
    }

    @Test
    fun insertDeleteAndFilteredEmptyRecordsAreSeparated() {
        val removed = node("removed").let { it.copy(messages = it.messages + message("hidden branch")) }
        val retained = node("retained")
        val empty = node("").copy(messages = emptyList())
        val added = node("added")
        val plan = planMessageNodeWrites(conversationId.toString(), stored(listOf(removed, retained, empty)), listOf(retained, added))
        assertEquals(listOf(removed.id.toString()), plan.deletedIds)
        assertEquals(listOf(added.id.toString()), plan.inserts.map { it.id })
        assertEquals(2, plan.previousFtsNodes.single().messages.size)
        assertFalse(empty.id.toString() in plan.deletedIds)
    }

    @Test
    fun reasoningAndSelectionDoNotChangeSearchText() {
        val original = node("visible")
        val changed = original.copy(messages = listOf(original.messages.single().copy(
            parts = original.messages.single().parts + UIMessagePart.Reasoning(reasoning = "private reasoning"),
        )))
        val plan = planMessageNodeWrites(conversationId.toString(), stored(listOf(original)), listOf(changed))
        assertEquals(1, plan.updates.size)
        assertTrue(planFtsNodeMutations(plan.previousFtsNodes, plan.currentFtsNodes).isEmpty())
    }

    @Test
    fun searchTextRemovalAndBranchAdditionsAreIndexed() {
        val original = node("before")
        val changed = original.copy(messages = listOf(original.messages.single().copy(parts = emptyList()), message("new branch")))
        val mutations = planFtsNodeMutations(listOf(original), listOf(changed))
        assertEquals(1, mutations.size)
        assertEquals(listOf("new branch"), mutations.single().messages.map { it.text })
        assertTrue(planFtsNodeMutations(listOf(changed), emptyList()).single().messages.isEmpty())
    }

    @Test
    fun textPastExistingIndexLimitDoesNotRewriteIndex() {
        val prefix = "x".repeat(10_000)
        val original = node(prefix + "before")
        val changed = original.copy(messages = listOf(original.messages.single().copy(parts = listOf(UIMessagePart.Text(prefix + "after")))))
        assertTrue(planFtsNodeMutations(listOf(original), listOf(changed)).isEmpty())
    }

    @Test
    fun metadataPatchPreservesStreamingNodesAndUnrelatedFields() {
        val before = Conversation.ofId(conversationId, Uuid.random()).copy(title = "old")
        val change = ConversationMetadataChange(before, before.copy(title = "new"))
        val current = before.copy(messageNodes = listOf(node("streaming")), chatSuggestions = listOf("suggestion"), folderId = Uuid.random())
        val result = change.applyTo(current)
        assertEquals("new", result.title)
        assertSame(current.messageNodes, result.messageNodes)
        assertSame(current.chatSuggestions, result.chatSuggestions)
        assertEquals(current.folderId, result.folderId)
    }

    @Test
    fun metadataUpdateKeepsAnAssignmentEvenWhenDatabaseAlreadyHasItsValue() {
        val disk = Conversation.ofId(conversationId, Uuid.random()).copy(chatSuggestions = listOf("same result"))
        val memory = disk.copy(chatSuggestions = emptyList())
        val update: (Conversation) -> Conversation = { it.copy(chatSuggestions = listOf("same result")) }
        val memoryChange = ConversationMetadataChange(memory, update(memory))
        val persisted = memoryChange.applyTo(update(disk))
        val diskChange = ConversationMetadataChange(disk, persisted)
        assertEquals(listOf("same result"), memoryChange.applyTo(diskChange.applyTo(memory)).chatSuggestions)
    }

    @Test
    fun metadataUpdateMergesPendingBindingsAndStillClearsAnOlderDatabaseValue() {
        val disk = Conversation.ofId(conversationId, Uuid.random())
        val pendingId = Uuid.random()
        val addedId = Uuid.random()
        val memory = disk.copy(modeInjectionIds = setOf(pendingId))
        val update: (Conversation) -> Conversation = { it.copy(modeInjectionIds = it.modeInjectionIds + addedId) }
        val memoryChange = ConversationMetadataChange(memory, update(memory))
        assertEquals(setOf(pendingId, addedId), memoryChange.applyTo(update(disk)).modeInjectionIds)
        val cleared = disk.copy(chatSuggestions = emptyList())
        val noChange = ConversationMetadataChange(cleared, cleared.copy(chatSuggestions = emptyList()))
        val oldDisk = disk.copy(chatSuggestions = listOf("old"))
        val updatedDisk = noChange.applyTo(oldDisk.copy(chatSuggestions = emptyList()))
        assertTrue(updatedDisk.chatSuggestions.isEmpty())
    }

    @Test
    fun coldSessionReplaysCommittedToggleAsAssignment() = runBlocking {
        val before = Conversation.ofId(conversationId, Uuid.random()).copy(isPinned = false)
        val committed = before.copy(isPinned = true)
        val change = ConversationMetadataChange(before, committed)
        val session = ConversationSession(conversationId, before, this, log = {}) {}
        session.update(change::applyTo)
        assertTrue(session.initialize { committed }.isPinned)
        assertTrue(change.applyTo(change.applyTo(committed)).isPinned)
        session.cleanup()
    }

    @Test
    fun queuedSaveReadsStateOnlyAfterAcquiringPersistenceLock() = runBlocking {
        val initial = Conversation.ofId(conversationId, Uuid.random()).copy(title = "first")
        val session = ConversationSession(conversationId, initial, this, log = {}) {}
        session.replace(initial)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            session.withRefSuspend {
                session.withPersistenceLock {
                    val snapshot = session.state.value
                    entered.complete(Unit)
                    finish.await()
                    writes.add(snapshot.title)
                }
            }
        }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            session.withRefSuspend { session.withPersistenceLock { writes.add(session.state.value.title) } }
        }
        assertTrue(session.isInUse)
        assertTrue(writes.isEmpty())
        session.update { it.copy(title = "latest") }
        finish.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first", "latest"), writes)
        assertFalse(session.isInUse)
        session.cleanup()
    }

    @Test
    fun folderMoveUsesFreshLookupRatherThanAnEarlierUiCheck() = runBlocking {
        val before = Conversation.ofId(conversationId, Uuid.random())
        val target = Uuid.random()
        var folderAvailable = true
        val uiSawFolder = folderAvailable
        val lookup: suspend (Uuid) -> String? = {
            if (folderAvailable) before.assistantId.toString() else null
        }
        folderAvailable = false
        assertTrue(uiSawFolder)
        val failure = runCatching {
            validateConversationFolderChange(before, before.copy(folderId = target), lookup)
        }.exceptionOrNull()
        assertTrue(failure is me.rerere.rikkahub.web.NotFoundException)
    }

    @Test
    fun folderValidationSkipsUnchangedMetadataAndChecksNewOwnership() = runBlocking {
        val before = Conversation.ofId(conversationId, Uuid.random()).copy(folderId = Uuid.random())
        validateConversationFolderChange(before, before.copy(title = "new title")) {
            error("修改标题不需要查询文件夹")
        }
        validateConversationFolderChange(before, before.copy(folderId = null)) {
            error("清空文件夹不需要查询目标")
        }
        val failure = runCatching {
            validateConversationFolderChange(before, before.copy(folderId = Uuid.random())) {
                Uuid.random().toString()
            }
        }.exceptionOrNull()
        assertTrue(failure is me.rerere.rikkahub.web.BadRequestException)
        var lookups = 0
        validateConversationFolderChange(before, before.copy(folderId = Uuid.random())) {
            lookups++
            before.assistantId.toString()
        }
        assertEquals(1, lookups)
    }

    @Test
    fun folderRemovalWaitsForAnEarlierSaveAndLaterMutationsKeepItCleared() = runBlocking {
        val folder = Uuid.random()
        val initial = Conversation.ofId(conversationId, Uuid.random()).copy(folderId = folder)
        val session = ConversationSession(conversationId, initial, this, log = {}) {}
        session.replace(initial)
        val finish = CompletableDeferred<Unit>()
        val writes = mutableListOf<Uuid?>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            session.withRefSuspend {
                session.withPersistenceLock {
                    val snapshot = session.state.value
                    finish.await()
                    writes.add(snapshot.folderId)
                }
            }
        }
        val removal = async(start = CoroutineStart.UNDISPATCHED) {
            session.withRefSuspend {
                session.withPersistenceLock {
                    session.update { if (it.folderId == folder) it.copy(folderId = null) else it }
                    writes.add(session.state.value.folderId)
                }
            }
        }
        assertEquals(folder, session.state.value.folderId)
        finish.complete(Unit)
        first.await()
        removal.await()
        session.withRefSuspend {
            session.withPersistenceLock {
                session.update { it.copy(messageNodes = it.messageNodes + node("new message")) }
                writes.add(session.state.value.folderId)
            }
        }
        assertEquals(listOf(folder, null, null), writes)
        session.cleanup()
    }

    @Test
    fun folderRemovalDuringInitialLoadingIsNotLostToThePlaceholder() = runBlocking {
        val folder = Uuid.random()
        val placeholder = Conversation.ofId(conversationId, Uuid.random())
        val loaded = placeholder.copy(folderId = folder, messageNodes = listOf(node("history")))
        val session = ConversationSession(conversationId, placeholder, this, log = {}) {}
        val gate = CompletableDeferred<Unit>()
        val initialization = async(start = CoroutineStart.UNDISPATCHED) {
            session.initialize { gate.await(); loaded }
        }
        session.withRefSuspend {
            session.withPersistenceLock {
                session.update { if (it.folderId == folder) it.copy(folderId = null) else it }
            }
        }
        gate.complete(Unit)
        val result = initialization.await()
        assertEquals(null, result.folderId)
        assertSame(loaded.messageNodes, result.messageNodes)
        session.cleanup()
    }

    @Test
    fun failedSaveReleasesPersistenceLockAndKeepsLatestState() = runBlocking {
        val initial = Conversation.ofId(conversationId, Uuid.random())
        val session = ConversationSession(conversationId, initial, this, log = {}) {}
        val failure = runCatching {
            session.withRefSuspend { session.withPersistenceLock { error("write failed") } }
        }
        assertTrue(failure.isFailure)
        session.update { it.copy(title = "retry") }
        val retried = session.withRefSuspend { session.withPersistenceLock { session.state.value.title } }
        assertEquals("retry", retried)
        assertFalse(session.isInUse)
        session.cleanup()
    }
}
