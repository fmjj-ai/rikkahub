package me.rerere.rikkahub.data.ai.account

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

// 与 Codex CLI 一致：access token 过期前 5 分钟内就刷新
private const val TOKEN_REFRESH_LEEWAY_MS = 5 * 60_000L
private val AUTHORIZATION_TIMEOUT = 5.minutes

sealed interface AccountLoginStatus {
    data object Authorizing : AccountLoginStatus

    data class Failed(val message: String) : AccountLoginStatus
}

/**
 * 管理账号型提供商（[ProviderSetting.Account]）的登录、token 刷新和凭据持久化。
 *
 * 凭据只由这里写回设置，界面编辑提供商时应保留设置里的最新凭据，见 [preserveCredential]。
 */
class ProviderAccountManager(
    private val settingsStore: SettingsStore,
    private val appScope: CoroutineScope,
    client: OkHttpClient,
) : AccountCredentialProvider {
    // 走应用的 OkHttpClient，授权和刷新请求也能用上设置里的代理
    // 401 鉴权在推理请求线程里等待刷新；刷新使用独立调度器，避免并发请求占满槽位后互相等待。
    private val oauthClient = OAuthHttpClient(client.newBuilder().dispatcher(Dispatcher()).build())
    private val chatGPTCallbackServer = OAuthLoopbackCallbackServer(
        port = ChatGPTAuth.CALLBACK_PORT,
        callbackPath = ChatGPTAuth.CALLBACK_PATH,
        redirectHost = ChatGPTAuth.CALLBACK_HOST,
    )
    private val loginJobs = ConcurrentHashMap<Uuid, Job>()
    private val loginAttempts = ConcurrentHashMap<Uuid, Any>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    private val _loginStatus = MutableStateFlow<Map<Uuid, AccountLoginStatus>>(emptyMap())
    val loginStatus: StateFlow<Map<Uuid, AccountLoginStatus>> = _loginStatus.asStateFlow()

    fun startLogin(providerId: Uuid, context: Context) {
        val provider = findAccount(providerId) ?: return
        loginJobs.remove(providerId)?.cancel()
        val attempt = Any()
        loginAttempts[providerId] = attempt
        setStatus(providerId, AccountLoginStatus.Authorizing)
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                val credential = when (provider) {
                    is ProviderSetting.ChatGPT -> loginChatGPT(context.applicationContext)
                }
                persistCredential(providerId, credential, attempt)
                if (loginAttempts.remove(providerId, attempt)) setStatus(providerId, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loginAttempts.remove(providerId, attempt)) {
                    setStatus(providerId, AccountLoginStatus.Failed(e.message ?: e.javaClass.simpleName))
                }
            }
        }
        loginJobs[providerId] = job
        job.invokeOnCompletion { loginJobs.remove(providerId, job) }
        job.start()
    }

    fun cancelLogin(providerId: Uuid) {
        loginAttempts.remove(providerId)
        loginJobs.remove(providerId)?.cancel()
        setStatus(providerId, null)
    }

    suspend fun logout(providerId: Uuid) {
        cancelLogin(providerId)
        persistCredential(providerId, null)
    }

    /**
     * 需要刷新时在 [appScope] 里执行，调用方被取消（停止生成、关掉额度界面）时打断不了已经发出的刷新：
     * 服务端一收到请求就会轮换 refresh token，新的没存下来就只能重新登录。
     */
    override suspend fun getCredential(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
    ): OAuthCredential {
        // 绝大多数请求的 token 都还有效，直接返回，不用切线程排队拿锁
        findAccount(setting.id)?.credential
            ?.takeUnless { it.needsRefresh(rejectedAccessToken) }
            ?.let { return it }
        return appScope.async(Dispatchers.IO) {
            refreshIfNeeded(setting, rejectedAccessToken)
        }.await()
    }

    /**
     * 按提供商串行刷新。拿到锁后重新读设置：并发请求可能已经刷新过，refresh token 只能用一次。
     */
    private suspend fun refreshIfNeeded(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
    ): OAuthCredential {
        val lock = refreshLocks.computeIfAbsent(setting.id) { Mutex() }
        return lock.withLock {
            val latest = findAccount(setting.id) ?: error("提供商已删除，请重新添加并登录")
            val credential = latest.credential ?: error("${latest.name}：请先登录")
            if (!credential.needsRefresh(rejectedAccessToken)) return@withLock credential

            val refreshToken = credential.refreshToken
                ?: error("${latest.name}：登录已过期，请重新登录")
            val refreshed = try {
                when (latest) {
                    is ProviderSetting.ChatGPT -> ChatGPTAuth.toCredential(
                        token = oauthClient.refreshToken(ChatGPTAuth.refreshRequest(refreshToken)),
                        previous = credential,
                    )

                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "${latest.name}：刷新登录失败，请检查网络或重新登录",
                    e
                )
            }
            // 刷新期间用户可能退出或换了账号，这时结果作废，不能把旧会话写回去
            if (!replaceCredential(latest.id, expected = credential, credential = refreshed)) {
                error("${latest.name}：登录状态已更改，请重试")
            }
            refreshed
        }
    }

    private suspend fun loginChatGPT(context: Context): OAuthCredential =
        withContext(Dispatchers.IO) {
            val pkce = oauthClient.generatePkce()
            val state = oauthClient.generateState()
            val session = chatGPTCallbackServer.openSession(context, state)
            try {
                val authorizationUrl = oauthClient.buildAuthorizationUrl(
                    ChatGPTAuth.authorizationRequest(
                        pkce = pkce,
                        state = state,
                        redirectUri = session.redirectUri
                    )
                )
                withContext(Dispatchers.Main) {
                    CustomTabsOAuthAuthorizationLauncher.launch(context, authorizationUrl)
                }
                val callback =
                    session.awaitCallback(AUTHORIZATION_TIMEOUT) ?: error("授权已超时，请重新登录")
                callback.error?.let { code ->
                    error(listOfNotNull(code, callback.errorDescription).joinToString(": "))
                }
                val code = callback.code ?: error("未收到授权码，请重新登录")
                val token = oauthClient.exchangeAuthorizationCode(
                    OAuthHttpClient.AuthorizationCodeTokenRequest(
                        tokenEndpoint = ChatGPTAuth.TOKEN_ENDPOINT,
                        clientId = ChatGPTAuth.CLIENT_ID,
                        code = code,
                        codeVerifier = pkce.verifier,
                        redirectUri = session.redirectUri,
                    )
                )
                ChatGPTAuth.toCredential(token, previous = null)
            } finally {
                withContext(NonCancellable) { session.close() }
            }
        }

    private suspend fun persistCredential(providerId: Uuid, credential: OAuthCredential?, attempt: Any? = null) {
        settingsStore.update { old ->
            // 已取消或被新登录替代的回调，不能在退出登录后把旧会话写回来。
            if (attempt != null && loginAttempts[providerId] !== attempt) return@update old
            old.copy(
                providers = old.providers.map { provider ->
                    if (provider.id == providerId && provider is ProviderSetting.Account) {
                        provider.withCredential(credential)
                    } else {
                        provider
                    }
                }
            )
        }
    }

    /** 设置里的凭据仍是 [expected] 时才替换，返回是否替换了 */
    private suspend fun replaceCredential(
        providerId: Uuid,
        expected: OAuthCredential,
        credential: OAuthCredential,
    ): Boolean {
        var replaced = false
        settingsStore.update { old ->
            old.copy(
                providers = old.providers.map { provider ->
                    if (provider.id == providerId && provider is ProviderSetting.Account &&
                        provider.credential == expected) {
                        replaced = true
                        provider.withCredential(credential)
                    } else {
                        provider
                    }
                }
            )
        }
        return replaced
    }

    // 刚被服务端拒绝，或者快过期了
    private fun OAuthCredential.needsRefresh(rejectedAccessToken: String?): Boolean =
        accessToken == rejectedAccessToken ||
            (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - TOKEN_REFRESH_LEEWAY_MS)

    private fun findAccount(providerId: Uuid): ProviderSetting.Account? =
        settingsStore.settingsFlow.value.providers
            .find { it.id == providerId } as? ProviderSetting.Account

    private fun setStatus(providerId: Uuid, status: AccountLoginStatus?) {
        _loginStatus.update { if (status == null) it - providerId else it + (providerId to status) }
    }
}

/**
 * 界面整份写回提供商时用：凭据只能由 [ProviderAccountManager] 修改，
 * 编辑期间后台可能已经刷新过 token，旧凭据写回会把轮换后的 refresh token 覆盖掉。
 */
fun ProviderSetting.preserveCredential(latest: ProviderSetting): ProviderSetting =
    if (this is ProviderSetting.Account && latest is ProviderSetting.Account) {
        withCredential(latest.credential)
    } else {
        this
    }
