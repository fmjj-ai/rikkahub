package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

/** OpenAI Images API 的 partial_images 取值范围为 1..3 */
private const val MAX_PARTIAL_IMAGES = 3

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = buildJsonObject {
            put("model", params.model.modelId)
            put("prompt", params.prompt)
            put("n", params.numOfImages)
            if (params.size.isNotBlank()) {
                put("size", params.size)
            }
        }.mergeCustomBody(params.customBody)

        Log.i(TAG, "generateImage: $requestBody")

        // 使用方只用支持流式生图的服务商：partialImages > 0 时直接走流式，
        // 不做"先探测、失败再回退"——中转若忽略 stream 字段照常出图，回退会造成一次画图重复计费。
        // 多张时各家流式语义不统一（partial_image_index 是半成品序号而非图片序号），n > 1 保持非流式。
        if (params.partialImages > 0 && params.numOfImages == 1) {
            streamImageGeneration(providerSetting, params, requestBody, key).collect { emit(it) }
            return@flow
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    /**
     * OpenAI Images API 的流式生图（`stream: true` + `partial_images`）。
     *
     * 事件形态：
     * - `image_generation.partial_image`：半成品，带 `partial_image_index`
     * - `image_generation.completed`：最终图
     */
    private fun streamImageGeneration(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        requestBody: JsonObject,
        key: String,
    ): Flow<ImageGenerationItem> = callbackFlow {
        val streamBody = buildJsonObject {
            requestBody.forEach { (name, value) -> put(name, value) }
            put("stream", true)
            put("partial_images", params.partialImages.coerceIn(1, MAX_PARTIAL_IMAGES))
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(streamBody).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamImageGeneration: ${json.encodeToString(streamBody)}")

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                val item = runCatching { parseImageGenerationEvent(data) }.getOrElse {
                    Log.w(TAG, "onEvent: failed to parse image stream event: $data", it)
                    return
                } ?: return
                trySend(item)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val bodyRaw = response?.body?.stringSafe()
                val exception = if (!bodyRaw.isNullOrBlank()) {
                    runCatching { json.parseToJsonElement(bodyRaw).parseErrorDetail() }
                        .getOrElse { t ?: IllegalStateException("Image generation stream failed: ${response?.code}") }
                } else {
                    t ?: IllegalStateException("Image generation stream failed: ${response?.code}")
                }
                close(exception)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose { eventSource.cancel() }
    }.buffer(Channel.UNLIMITED)

    /** 解析单个流式事件；非图片事件（如心跳、错误包装）返回 null */
    private fun parseImageGenerationEvent(data: String): ImageGenerationItem? {
        val payload = data.trim().split("\n")
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it["b64_json"] != null }
            ?: return null

        val b64Json = payload["b64_json"]?.jsonPrimitive?.contentOrNull ?: return null
        val outputFormat = payload["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val partialIndex = payload["partial_image_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val isPartial = partialIndex != null ||
            payload["type"]?.jsonPrimitive?.contentOrNull?.contains("partial") == true

        return ImageGenerationItem(
            data = b64Json,
            mimeType = outputFormat.toImageMimeType(),
            partial = isPartial,
            partialImageIndex = partialIndex,
        )
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        if (params.size.isNotBlank()) {
            bodyBuilder.addFormDataPart("size", params.size)
        }

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to edit image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
