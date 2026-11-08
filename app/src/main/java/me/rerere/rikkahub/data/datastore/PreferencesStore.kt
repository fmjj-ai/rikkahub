package me.rerere.rikkahub.data.datastore

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration()
        )
    }
)

class SettingsStore internal constructor(
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    scope: kotlinx.coroutines.CoroutineScope,
    private val invalidateTemplates: () -> Unit = {},
    private val reportLoadError: (Throwable) -> Unit = {},
) {
    constructor(context: Context, scope: AppScope) : this(
        context.settingsStore,
        scope,
        { org.koin.core.context.GlobalContext.get().get<PebbleEngine>().templateCache.invalidateAll() },
        { Log.e(TAG, "Failed to load settings", it); Unit },
    )
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")
        val MEDIA_GENERATION_PROVIDERS = stringPreferencesKey("media_generation_providers")
        val UPLOAD_S3_CONFIG = stringPreferencesKey("upload_s3_config")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
    }

    private val state = kotlinx.coroutines.flow.MutableStateFlow(Settings.dummy())
    private val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val stateLock = Any()
    private val persistMutex = kotlinx.coroutines.sync.Mutex()
    private var persisted: Settings? = null

    val settingsFlow: kotlinx.coroutines.flow.StateFlow<Settings> = state.asStateFlow()

    // 保留旧读取入口，但加载后只观察内存，不再把滞后的磁盘快照写回界面。
    val settingsFlowRaw: kotlinx.coroutines.flow.Flow<Settings> = kotlinx.coroutines.flow.flow {
        awaitLoaded()
        emitAll(settingsFlow)
    }

    init {
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val settings = dataStore.data.retryWhen { error, attempt ->
                    val retry = error is IOException &&
                        error !is androidx.datastore.core.CorruptionException && attempt < 3
                    if (retry) kotlinx.coroutines.delay(100L shl attempt.toInt())
                    retry
                }.first().toSettings().normalized()
                invalidateTemplates()
                state.value = settings
                loaded.complete(Unit)
            } catch (error: Throwable) {
                // 读取失败不伪造空配置，任何写入都由 awaitLoaded 阻止。
                loaded.completeExceptionally(error)
                if (error is kotlinx.coroutines.CancellationException) throw error
                reportLoadError(error)
            }
        }
    }

    suspend fun awaitLoaded(): Settings {
        loaded.await()
        return state.value
    }

    /** 仅用于明确的完整备份恢复；普通设置修改必须使用基于最新值的 update。 */
    suspend fun replace(settings: Settings) {
        if (settings.init) return
        update { settings }
    }

    /** 基于最新内存值原子更新；页面取消后仍完成落盘。 */
    suspend fun update(fn: (Settings) -> Settings) {
        awaitLoaded()
        synchronized(stateLock) {
            val updated = fn(state.value)
            if (updated.init) return
            val normalized = updated.normalized()
            state.value = if (normalized == updated) updated else normalized
        }
        invalidateTemplates()
        withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Default) {
            persistMutex.withLock {
                val latest = state.value
                if (latest !== persisted) {
                    dataStore.edit { it.putSettings(latest, persisted) }
                    persisted = latest
                }
            }
        }
    }

    /** 外观文件只在保存成功后回收；当前内存和最后成功落盘的引用都必须已经解除。 */
    suspend fun updateWithAppearanceCleanup(
        fileIdentity: (String) -> String?,
        deleteFiles: (List<String>) -> Unit,
        fn: (Settings) -> Settings,
    ) {
        awaitLoaded()
        withContext(kotlinx.coroutines.NonCancellable) {
            var candidates = emptySet<String>()
            update { current ->
                fn(current).also { updated ->
                    candidates = current.appearanceFileUrls() - updated.appearanceFileUrls()
                }
            }
            if (candidates.isNotEmpty()) {
                persistMutex.withLock {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // 与状态发布共用锁，引用复查和实际删除之间不能接受新的文件引用。
                        synchronized(stateLock) {
                            val referenced = (state.value.appearanceFileUrls() +
                                persisted?.appearanceFileUrls().orEmpty()).mapNotNull(fileIdentity).toSet()
                            val unused = candidates.mapNotNull(fileIdentity).toSet() - referenced
                            if (unused.isNotEmpty()) deleteFiles(unused.toList())
                        }
                    }
                }
            }
        }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        update { it.copy(assistantId = assistantId) }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}
