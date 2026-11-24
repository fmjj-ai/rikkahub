package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertTrue
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class SettingsStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    // 这些测试靠卡住读写来排时序，出问题时表现为一直挂着
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private var opened: OpenedStore? = null

    @After
    fun tearDown() {
        runBlocking { opened?.close() }
    }

    // 同一个文件同一时间只能有一个 DataStore，重新打开前先关掉上一个
    private suspend fun open(loadGate: CompletableDeferred<Unit>? = null): OpenedStore {
        opened?.close()
        return OpenedStore(File(temp.root, "settings.preferences_pb"), loadGate).also { opened = it }
    }

    private suspend fun reopened(): Settings = open().store.awaitLoaded()

    @Test
    fun `typing faster than the disk can write loses no keystroke`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.update { it.copy(titlePrompt = "") }
        opened.disk.writeDelayMillis = 20

        val typing = "hello world".map { char ->
            // 和输入框一样：在当前显示的值后面追加，不等上一次写盘
            val shown = store.settingsFlow.value.titlePrompt
            launch(start = CoroutineStart.UNDISPATCHED) {
                store.update { it.copy(titlePrompt = shown + char) }
            }.also { delay(5) }
        }
        typing.joinAll()

        assertEquals("hello world", store.settingsFlow.value.titlePrompt)
        assertEquals("hello world", reopened().titlePrompt)
    }

    @Test
    fun `concurrent updates are all applied`() = runBlocking {
        val store = open().store
        store.update { it.copy(webServerPort = 0) }

        (1..8).map {
            launch(Dispatchers.Default) {
                repeat(50) { store.update { it.copy(webServerPort = it.webServerPort + 1) } }
            }
        }.joinAll()

        assertEquals(400, store.settingsFlow.value.webServerPort)
        assertEquals(400, reopened().webServerPort)
    }

    @Test
    fun `changes made during a slow write are flushed in a single write`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.awaitLoaded()
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate

        val first = launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(webServerPort = 9001) } }
        while (opened.disk.writes.get() == 0) delay(1)
        val rest = (9002..9050).map { port ->
            launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(webServerPort = port) } }
        }
        gate.complete(Unit)
        (rest + first).joinAll()

        assertEquals(2, opened.disk.writes.get())
        assertEquals(9050, store.settingsFlow.value.webServerPort)
        assertEquals(9050, reopened().webServerPort)
    }

    @Test
    fun `fields that did not change are not rewritten`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.update { it.copy(titlePrompt = "first") }
        // 绕过 store 直接改盘上的值：之后的写入如果把没变的字段也重写一遍，它就会被内存里的值盖回去
        opened.disk.edit { it[SettingsStore.OCR_PROMPT] = "on disk" }

        store.update { it.copy(titlePrompt = "second") }

        val stored = opened.disk.data.first()
        assertEquals("second", stored[SettingsStore.TITLE_PROMPT])
        assertEquals("on disk", stored[SettingsStore.OCR_PROMPT])
    }

    @Test
    fun `values repaired while loading are written back by the next update`() = runBlocking {
        // 盘上留着一个越界的下标，读盘时会被收回 0。它必须跟着下一次写入落盘，
        // 否则以后搜索服务变多、这个下标重新落在范围内时，重启后选中的服务会悄悄变掉
        open().disk.edit { it[SettingsStore.SEARCH_SELECTED] = 3 }
        val opened = open()
        val store = opened.store
        assertEquals(0, store.awaitLoaded().searchServiceSelected)

        store.update { it.copy(titlePrompt = "anything") }

        assertEquals(0, opened.disk.data.first()[SettingsStore.SEARCH_SELECTED])
    }

    @Test
    fun `objects that an update did not touch keep their identity`() = runBlocking {
        val store = open().store
        val before = store.awaitLoaded()

        store.update { it.copy(titlePrompt = "anything") }

        val after = store.settingsFlow.value
        assertSame(before.providers, after.providers)
        assertSame(before.assistants, after.assistants)
    }

    @Test
    fun `update issued before settings are loaded is applied on top of the stored settings`() = runBlocking {
        open().store.update { it.copy(titlePrompt = "custom") }

        val gate = CompletableDeferred<Unit>()
        val store = open(loadGate = gate).store
        val assistantId = DEFAULT_ASSISTANTS.last().id
        val update = launch(start = CoroutineStart.UNDISPATCHED) { store.updateAssistant(assistantId) }
        val loadedBeforeGate = !store.settingsFlow.value.init
        gate.complete(Unit)
        update.join()

        assertFalse(loadedBeforeGate)
        assertEquals("custom", store.settingsFlow.value.titlePrompt)
        assertEquals(assistantId, store.settingsFlow.value.assistantId)
        val stored = reopened()
        assertEquals("custom", stored.titlePrompt)
        assertEquals(assistantId, stored.assistantId)
    }

    @Test
    fun `missing injection key keeps the custom version default`() = runBlocking {
        val store = open().store
        assertEquals(emptyList<Any>(), store.awaitLoaded().modeInjections)

        store.update { it.copy(modeInjections = emptyList()) }

        assertEquals(emptyList<Any>(), reopened().modeInjections)
    }

    @Test
    fun `settings are normalized as soon as they are updated`() = runBlocking {
        val store = open().store

        store.update {
            it.copy(providers = emptyList(), favoriteModels = listOf(Uuid.random()), searchServiceSelected = 5)
        }

        val settings = store.settingsFlow.value
        assertEquals(DEFAULT_PROVIDERS.map { it.id }, settings.providers.map { it.id })
        assertEquals(emptyList<Uuid>(), settings.favoriteModels)
        assertEquals(0, settings.searchServiceSelected)
    }

    @Test
    fun `change is still written when its caller is cancelled`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.awaitLoaded()
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate

        // 页面关闭时 viewModelScope 里还没写完的修改
        val update = launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(titlePrompt = "kept") } }
        update.cancel()
        gate.complete(Unit)
        update.join()

        assertEquals("kept", reopened().titlePrompt)
    }
    @Test
    fun assistantFieldTransformsMergeWhileTheFirstWriteIsBlocked() = runBlocking {
        val opened = open()
        val store = opened.store
        val subject = Assistant(name = "before")
        store.update { it.copy(assistants = listOf(subject), assistantId = subject.id) }
        val previousWrites = opened.disk.writes.get()
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            store.update { it.updateAssistant(subject.id) { assistant -> assistant.copy(name = "renamed") } }
        }
        while (opened.disk.writes.get() == previousWrites) delay(1)
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            store.update { it.updateAssistant(subject.id) { assistant -> assistant.copy(systemPrompt = "new prompt") } }
        }
        gate.complete(Unit)
        listOf(first, second).joinAll()
        val result = reopened().assistants.single { it.id == subject.id }
        assertEquals("renamed", result.name)
        assertEquals("new prompt", result.systemPrompt)
    }

    @Test
    fun staleAssistantEditDoesNotRecreateADeletedAssistant() = runBlocking {
        val store = open().store
        val removed = Assistant(name = "removed")
        val retained = Assistant(name = "retained")
        store.update { it.copy(assistants = listOf(removed, retained), assistantId = retained.id) }
        store.update { it.copy(assistants = it.assistants.filterNot { assistant -> assistant.id == removed.id }) }
        store.update { it.updateAssistant(removed.id) { assistant -> assistant.copy(name = "late edit") } }
        assertFalse(reopened().assistants.any { it.id == removed.id })
    }

    @Test
    fun nestedUpdatesKeepOtherDisplayNetworkAndBackupFields() = runBlocking {
        val store = open().store
        store.update { latest -> latest.copy(
            displaySetting = latest.displaySetting.copy(showUserAvatar = false),
            networkSetting = latest.networkSetting.copy(userAgent = "custom-agent"),
            webDavConfig = latest.webDavConfig.copy(username = "user"),
            s3Config = latest.s3Config.copy(region = "region"),
        ) }
        store.update { latest -> latest.copy(
            displaySetting = latest.displaySetting.copy(showDateTimeInMessage = true),
            networkSetting = latest.networkSetting.copy(enableAutoRetry = true),
            webDavConfig = latest.webDavConfig.copy(path = "backup-path"),
            s3Config = latest.s3Config.copy(bucket = "bucket"),
        ) }
        val result = reopened()
        assertFalse(result.displaySetting.showUserAvatar)
        assertTrue(result.displaySetting.showDateTimeInMessage)
        assertEquals("custom-agent", result.networkSetting.userAgent)
        assertTrue(result.networkSetting.enableAutoRetry)
        assertEquals("user", result.webDavConfig.username)
        assertEquals("backup-path", result.webDavConfig.path)
        assertEquals("region", result.s3Config.region)
        assertEquals("bucket", result.s3Config.bucket)
    }

    @Test
    fun appearanceCleanupKeepsSharedBackgroundAndUserAvatarReferences() = runBlocking {
        val store = open().store
        val url = "file:///shared-background.png"
        val first = Assistant(name = "first", background = url)
        val second = Assistant(name = "second", background = url)
        store.update { latest -> latest.copy(
            assistants = listOf(first, second),
            assistantId = first.id,
            displaySetting = latest.displaySetting.copy(userAvatar = Avatar.Image(url)),
        ) }
        val deleted = mutableListOf<String>()
        val cleanup: (List<String>) -> Unit = { deleted.addAll(it) }
        store.updateWithAppearanceCleanup(fileIdentity = { it }, deleteFiles = cleanup) {
            it.updateAssistant(first.id) { assistant -> assistant.copy(background = null) }
        }
        store.updateWithAppearanceCleanup(fileIdentity = { it }, deleteFiles = cleanup) {
            it.updateAssistant(second.id) { assistant -> assistant.copy(background = null) }
        }
        assertTrue(deleted.isEmpty())
        store.updateWithAppearanceCleanup(fileIdentity = { it }, deleteFiles = cleanup) {
            it.copy(displaySetting = it.displaySetting.copy(userAvatar = Avatar.Dummy))
        }
        assertEquals(listOf(url), deleted)
    }

    @Test
    fun appearanceFilesAreNotDeletedBeforeTheWriteSucceeds() = runBlocking {
        val opened = open()
        val store = opened.store
        val url = "file:///old-avatar.png"
        val subject = Assistant(avatar = Avatar.Image(url))
        store.update { it.copy(assistants = listOf(subject), assistantId = subject.id) }
        val previousWrites = opened.disk.writes.get()
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate
        val deleted = mutableListOf<String>()
        val change = launch(start = CoroutineStart.UNDISPATCHED) {
            store.updateWithAppearanceCleanup(fileIdentity = { it }, deleteFiles = { deleted.addAll(it) }) {
                it.updateAssistant(subject.id) { assistant -> assistant.copy(avatar = Avatar.Dummy) }
            }
        }
        try {
            while (opened.disk.writes.get() == previousWrites) delay(1)
            assertTrue(deleted.isEmpty())
        } finally {
            gate.complete(Unit)
        }
        change.join()
        assertEquals(listOf(url), deleted)
        assertEquals(Avatar.Dummy, reopened().assistants.single { it.id == subject.id }.avatar)
    }

    @Test
    fun failedAppearanceWriteKeepsBothOldFilesAndAcceptedInMemoryState() = runBlocking {
        val opened = open()
        val store = opened.store
        val subject = Assistant(background = "file:///old.png")
        store.update { it.copy(assistants = listOf(subject), assistantId = subject.id) }
        val failed = CompletableDeferred<Unit>()
        failed.completeExceptionally(java.io.IOException("write failed"))
        opened.disk.writeGate = failed
        val deleted = mutableListOf<String>()
        val result = runCatching {
            store.updateWithAppearanceCleanup(fileIdentity = { it }, deleteFiles = { deleted.addAll(it) }) {
                it.updateAssistant(subject.id) { assistant -> assistant.copy(background = "file:///new.png") }
            }
        }
        assertTrue(result.isFailure)
        assertTrue(deleted.isEmpty())
        assertEquals("file:///new.png", store.settingsFlow.value.assistants.single { it.id == subject.id }.background)
        opened.disk.writeGate = null
        store.update { it.copy(titlePrompt = "retry pending settings") }
        assertEquals("file:///new.png", reopened().assistants.single { it.id == subject.id }.background)
    }

    @Test
    fun acceptedAppearanceUpdateAndCleanupFinishAfterCallerCancellation() = runBlocking {
        val opened = open()
        val store = opened.store
        val url = "file:///old-background.png"
        val subject = Assistant(background = url)
        store.update { it.copy(assistants = listOf(subject), assistantId = subject.id) }
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate
        val deleted = mutableListOf<String>()
        val change = launch(start = CoroutineStart.UNDISPATCHED) {
            store.updateWithAppearanceCleanup(fileIdentity = { it }, deleteFiles = { deleted.addAll(it) }) {
                it.updateAssistant(subject.id) { assistant -> assistant.copy(background = null) }
            }
        }
        change.cancel()
        gate.complete(Unit)
        change.join()
        assertEquals(listOf(url), deleted)
        assertEquals(null, reopened().assistants.single { it.id == subject.id }.background)
    }

    @Test
    fun equivalentFileUrisKeepTheirSharedPhysicalFile() = runBlocking {
        val store = open().store
        val physicalFile = temp.newFile("shared image.png").canonicalFile
        val firstUrl = physicalFile.toURI().toString()
        val secondUrl = firstUrl.replaceFirst("file:/", "file:///")
        val first = Assistant(background = firstUrl)
        val second = Assistant(background = secondUrl)
        store.update { it.copy(assistants = listOf(first, second), assistantId = first.id) }
        val identity: (String) -> String? = { url ->
            runCatching { File(java.net.URI(url)).canonicalFile.toURI().toString() }.getOrNull()
        }
        assertEquals(identity(firstUrl), identity(secondUrl))
        val deleted = mutableListOf<String>()
        store.updateWithAppearanceCleanup(identity, { deleted.addAll(it) }) {
            it.updateAssistant(first.id) { assistant -> assistant.copy(background = null) }
        }
        assertTrue(deleted.isEmpty())
        store.updateWithAppearanceCleanup(identity, { deleted.addAll(it) }) {
            it.updateAssistant(second.id) { assistant -> assistant.copy(background = null) }
        }
        assertEquals(listOf(identity(firstUrl)), deleted)
    }

}

private class OpenedStore(file: File, loadGate: CompletableDeferred<Unit>?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val disk = GatedDataStore(PreferenceDataStoreFactory.create(scope = scope) { file }, loadGate)
    val store = SettingsStore(disk, scope)

    suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
}

// 可以卡住或拖慢读写的磁盘，用来确定地制造「内存已经改了、盘还没写完」的时刻
private class GatedDataStore(
    private val delegate: DataStore<Preferences>,
    private val loadGate: CompletableDeferred<Unit>?,
) : DataStore<Preferences> {
    val writes = AtomicInteger()

    @Volatile
    var writeGate: CompletableDeferred<Unit>? = null

    @Volatile
    var writeDelayMillis = 0L

    override val data: Flow<Preferences> = flow {
        loadGate?.await()
        emitAll(delegate.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        writes.incrementAndGet()
        writeGate?.await()
        delay(writeDelayMillis)
        return delegate.updateData(transform)
    }
}
