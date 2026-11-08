package me.rerere.ai.provider

import kotlinx.serialization.Transient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported")
    }

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /**
     * 快速模式 / 优先级模式：为 true 时 OpenAI 系请求写入 `service_tier: "priority"`。
     * 仅由聊天主请求设置；标题 / 建议 / 翻译 / 上下文压缩等后台调用一律保持 false。
     * 若自定义请求体中已存在 service_tier，则由用户显式配置覆盖（mergeCustomBody 后写入）。
     */
    val serviceTierPriority: Boolean = false,
    /** 账号端点的会话缓存标识；为空时由提供商使用首条用户消息的稳定 ID。 */
    val sessionId: String? = null,
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String,
    // 仅用于编辑期定位，copy 保留；不改变序列化字段或原有值相等语义。
    @Transient val editKey: Any = Any(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CustomHeader && name == other.name && value == other.value

    override fun hashCode(): Int = 31 * name.hashCode() + value.hashCode()

    override fun toString(): String = "CustomHeader(name=$name, value=$value)"
}

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement,
    // 不落盘的行身份，避免并发增删后把字段编辑应用到另一个下标。
    @Transient val editKey: Any = Any(),
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CustomBody && key == other.key && value == other.value

    override fun hashCode(): Int = 31 * key.hashCode() + value.hashCode()

    override fun toString(): String = "CustomBody(key=$key, value=$value)"
}
