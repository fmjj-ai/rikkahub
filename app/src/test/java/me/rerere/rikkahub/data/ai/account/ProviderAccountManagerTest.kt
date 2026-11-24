package me.rerere.rikkahub.data.ai.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.components.ui.encodeForShare
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProviderAccountManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private val initial = ProviderSetting.ChatGPT(
        credential = OAuthCredential("old-access", "old-refresh", expiresAt = 1, accountId = "account"),
    )

    private suspend fun withManager(
        beforeResponse: () -> Unit = {},
        body: suspend (ProviderAccountManager, SettingsStore, AtomicInteger) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val count = AtomicInteger()
        try {
            val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
                File(temp.root, "settings.preferences_pb")
            }, scope)
            store.update { it.copy(providers = listOf(initial)) }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                count.incrementAndGet()
                beforeResponse()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""".toResponseBody())
                    .build()
            }.build()
            body(ProviderAccountManager(store, scope, client), store, count)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test(timeout = 15000)
    fun `parallel expired requests rotate refresh token once and keep latest token`() = runBlocking {
        withManager { manager, store, count ->
            val results = (1..12).map { async(Dispatchers.Default) { manager.getCredential(initial, null) } }.awaitAll()
            assertEquals(1, count.get())
            assertTrue(results.all { it.accessToken == "new-access" })
            val latest = store.settingsFlow.value.providers.find { it.id == initial.id } as ProviderSetting.ChatGPT
            assertEquals("new-refresh", latest.credential!!.refreshToken)
            assertEquals("new-access", manager.getCredential(initial, "old-access").accessToken)
            assertEquals(1, count.get())
            assertEquals(latest.credential, (initial.copy(name = "编辑名称").preserveCredential(latest) as ProviderSetting.ChatGPT).credential)
        }
    }

    @Test(timeout = 15000)
    fun `logout during refresh prevents old session being restored`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        withManager(beforeResponse = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }) { manager, store, _ ->
            val result = async(Dispatchers.Default) { runCatching { manager.getCredential(initial, null) } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                manager.logout(initial.id)
            } finally { release.countDown() }
            assertTrue(result.await().isFailure)
            assertNull((store.settingsFlow.value.providers.find { it.id == initial.id } as ProviderSetting.ChatGPT).credential)
        }
    }

    @Test(timeout = 15000)
    fun `deleted provider does not fall back to stale copied credentials`() = runBlocking {
        withManager { manager, store, count ->
            store.update { settings -> settings.copy(providers = settings.providers.filterNot { it.id == initial.id }) }
            assertTrue(runCatching { manager.getCredential(initial, null) }.isFailure)
            assertEquals(0, count.get())
        }
    }

    @Test
    fun `sharing and importing never carry account credentials`() {
        assertNull((decodeProviderSetting(initial.encodeForShare()) as ProviderSetting.ChatGPT).credential)
        val raw = me.rerere.rikkahub.utils.JsonInstant.encodeToString<ProviderSetting>(initial)
        val imported = decodeProviderSetting("ai-provider:v1:" + kotlin.io.encoding.Base64.encode(raw.encodeToByteArray()))
        assertNull((imported as ProviderSetting.ChatGPT).credential)
    }
}
