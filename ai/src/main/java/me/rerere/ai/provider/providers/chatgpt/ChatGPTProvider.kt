package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.configureAccountAuth
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.providers.openai.ResponsesEndpoint
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.util.json
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException

internal const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
private const val CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
private const val CODEX_CLIENT_VERSION = "0.163.0"

internal val CODEX_CAPABILITIES = ResponseProviderCapabilities(
    systemMessageRole = "developer",
    supportsSamplingParams = false,
    alwaysIncludeEncryptedContent = true,
)

data class ChatGPTUsageWindow(val usedPercent: Double, val windowSeconds: Long?, val resetAtSeconds: Long?)
data class ChatGPTUsage(val windows: List<ChatGPTUsageWindow>, val limitReached: Boolean)

/** 只向固定的 ChatGPT/Codex 端点发送账号凭据。 */
class ChatGPTProvider(client: OkHttpClient, private val credentials: AccountCredentialProvider) :
    Provider<ProviderSetting.ChatGPT> {
    private val client = client.withAccountAuthenticator(credentials).newBuilder()
        .apply { interceptors().add(0, okhttp3.Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            // Codex 部分响应没有 Content-Type；仅在固定流端点成功且缺失类型时补齐。
            if (response.isSuccessful && response.request.url.toString() == "$CODEX_BASE_URL/responses" &&
                body.contentType() == null) {
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = body.source()
                }).build()
            } else response
        }) }.build()
    private val responseAPI = ResponseAPI(this.client)

    override suspend fun listModels(providerSetting: ProviderSetting.ChatGPT): List<Model> =
        parseCodexModels(fetch(providerSetting, "$CODEX_BASE_URL/models?client_version=$CODEX_CLIENT_VERSION"))

    suspend fun getUsage(providerSetting: ProviderSetting.ChatGPT): ChatGPTUsage =
        parseCodexUsage(fetch(providerSetting, CODEX_USAGE_URL))

    private suspend fun fetch(setting: ProviderSetting.ChatGPT, url: String): String {
        val request = Request.Builder().url(url).apply { configureAuth(setting) }.get().build()
        return client.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw IOException("ChatGPT HTTP ${response.code}: ${text.take(500)}")
            text
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> {
        val sessionId = params.sessionId ?: messages.firstOrNull { it.role == MessageRole.USER }?.id?.toString()
        return responseAPI.streamText(
            endpoint = ResponsesEndpoint(
                url = "$CODEX_BASE_URL/responses",
                capabilities = CODEX_CAPABILITIES,
                requireCompletion = true,
                configureRequest = {
                    configureAuth(providerSetting, params.customHeaders)
                    sessionId?.let { header("session-id", it) }
                },
            ),
            messages = messages,
            // 这些约束由后端固定，旧助手的自定义参数也不能重新引入它们。
            params = params.copy(sessionId = sessionId, customBody = params.customBody.filterNot {
                it.key in setOf("store", "stream", "instructions", "temperature", "top_p", "max_output_tokens")
            }),
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id)
        var usage: TokenUsage? = null
        var responseId = ""
        streamText(providerSetting, messages, params).collect { chunk ->
            message += chunk
            if (chunk.usage != null) usage = chunk.usage
            if (chunk.id.isNotBlank()) responseId = chunk.id
        }
        return MessageChunk(
            id = responseId,
            model = params.model.modelId,
            choices = listOf(UIMessageChoice(index = 0, message = message.copy(usage = usage), delta = null, finishReason = "stop")),
            usage = usage,
        )
    }

    private suspend fun Request.Builder.configureAuth(
        setting: ProviderSetting.ChatGPT,
        customHeaders: List<CustomHeader> = emptyList(),
    ) = configureAccountAuth(credentials, setting, customHeaders) { credential ->
        removeHeader("ChatGPT-Account-ID")
        credential.accountId?.let { header("ChatGPT-Account-ID", it) }
        header("originator", "codex_cli_rs")
    }
}

internal fun parseCodexModels(body: String): List<Model> {
    val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray.orEmpty().map { it.jsonObject }
    val visible = models.filter { it.stringOrNull("visibility") == "list" }.ifEmpty { models }
    return visible.mapNotNull { model ->
        val slug = model.stringOrNull("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val modalities = model["input_modalities"]?.jsonArray?.mapNotNull {
            when (it.jsonPrimitiveOrNull?.contentOrNull) {
                "text" -> Modality.TEXT
                "image" -> Modality.IMAGE
                else -> null
            }
        }?.ifEmpty { null } ?: listOf(Modality.TEXT, Modality.IMAGE)
        val reasoning = model["supported_reasoning_levels"]?.jsonArray?.isNotEmpty() == true
        val context = model["context_window"]?.jsonPrimitiveOrNull?.intOrNull?.takeIf { it > 0 }
            ?: Model().contextWindowTokens
        Model(
            modelId = slug,
            displayName = model.stringOrNull("display_name") ?: slug,
            inputModalities = modalities,
            abilities = if (reasoning) listOf(ModelAbility.TOOL, ModelAbility.REASONING) else listOf(ModelAbility.TOOL),
            contextWindowTokens = context,
            autoCompressThresholdTokens = minOf(Model().autoCompressThresholdTokens, (context * 0.8).toInt().coerceAtLeast(1)),
        )
    }.distinctBy { it.modelId }
}

internal fun parseCodexUsage(body: String): ChatGPTUsage {
    val rateLimit = json.parseToJsonElement(body).jsonObject["rate_limit"] as? JsonObject
    val windows = listOf("primary_window", "secondary_window").mapNotNull { key ->
        val window = rateLimit?.get(key) as? JsonObject ?: return@mapNotNull null
        val used = window["used_percent"]?.jsonPrimitiveOrNull?.doubleOrNull
            ?.takeIf { it.isFinite() } ?: return@mapNotNull null
        ChatGPTUsageWindow(
            usedPercent = used,
            windowSeconds = window["limit_window_seconds"]?.jsonPrimitiveOrNull?.longOrNull?.takeIf { it > 0 },
            resetAtSeconds = window["reset_at"]?.jsonPrimitiveOrNull?.longOrNull?.takeIf { it > 0 },
        )
    }
    return ChatGPTUsage(windows, rateLimit?.get("limit_reached")?.jsonPrimitiveOrNull?.booleanOrNull == true)
}

private fun JsonObject.stringOrNull(key: String): String? = get(key)?.jsonPrimitiveOrNull?.contentOrNull
