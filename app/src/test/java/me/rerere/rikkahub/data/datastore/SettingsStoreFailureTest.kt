package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SettingsStoreFailureTest {
    @Test
    fun `unreadable settings cannot be replaced with defaults`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val disk = object : DataStore<Preferences> {
            override val data = flow<Preferences> {
                reads.incrementAndGet()
                throw IOException("read failure")
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes.incrementAndGet()
                return emptyPreferences()
            }
        }
        try {
            val store = SettingsStore(disk, scope)
            assertTrue(runCatching { store.awaitLoaded() }.exceptionOrNull() is IOException)
            assertTrue(runCatching { store.update { it.copy(titlePrompt = "not saved") } }.isFailure)
            assertEquals(4, reads.get())
            assertEquals(0, writes.get())
            assertTrue(store.settingsFlow.value.init)
        } finally { scope.cancel() }
    }

    @Test
    fun `corruption is not retried and does not write defaults`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reads = AtomicInteger()
        val disk = object : DataStore<Preferences> {
            override val data = flow<Preferences> {
                reads.incrementAndGet()
                throw CorruptionException("corrupt data")
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("must not write after failed initialization")
        }
        try {
            val store = SettingsStore(disk, scope)
            assertTrue(runCatching { store.awaitLoaded() }.exceptionOrNull() is CorruptionException)
            assertEquals(1, reads.get())
        } finally { scope.cancel() }
    }

    @Test
    fun `a failed write is flushed together with the next update`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val disk = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            var fail = true
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (fail) throw IOException("write failure")
                return transform(data.value).also { data.value = it }
            }
        }
        try {
            val store = SettingsStore(disk, scope)
            assertTrue(runCatching { store.update { it.copy(titlePrompt = "keep this") } }.isFailure)
            disk.fail = false
            store.update { it.copy(webServerPort = 9090) }
            assertEquals("keep this", disk.data.value[SettingsStore.TITLE_PROMPT])
            assertEquals(9090, disk.data.value[SettingsStore.WEB_SERVER_PORT])
        } finally { scope.cancel() }
    }
}
