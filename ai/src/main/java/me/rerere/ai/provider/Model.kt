package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    /**
     * 多协议服务商下，这个模型走哪个协议。
     * null = 跟随服务商的 defaultProtocol；单协议（旧 typed）服务商忽略此字段。
     */
    val protocol: ProviderProtocol? = null,
    val contextWindowTokens: Int = DEFAULT_CONTEXT_WINDOW_TOKENS,
    val autoCompressThresholdTokens: Int = DEFAULT_AUTO_COMPRESS_THRESHOLD_TOKENS,
) {
    val contextWindow: Int get() = contextWindowTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    val autoCompressThreshold: Int
        get() = autoCompressThresholdTokens.takeIf { it > 0 }
            ?.coerceAtMost(contextWindow) ?: minOf(DEFAULT_AUTO_COMPRESS_THRESHOLD_TOKENS, contextWindow)
    val hasValidContextSettings: Boolean
        get() = contextWindowTokens > 0 && autoCompressThresholdTokens in 1..contextWindowTokens
}

const val DEFAULT_CONTEXT_WINDOW_TOKENS = 1_000_000
const val DEFAULT_AUTO_COMPRESS_THRESHOLD_TOKENS = 472_000

/** 官方多关键词搜索：每个关键词均须命中模型 ID 或显示名，顺序与大小写不限。 */
fun List<Model>.filterByKeywords(query: String): List<Model> {
    val keywords = query.split(" ").filter { it.isNotBlank() }
    if (keywords.isEmpty()) return this
    return filter { model ->
        keywords.all { keyword ->
            model.modelId.contains(keyword, ignoreCase = true) ||
                model.displayName.contains(keyword, ignoreCase = true)
        }
    }
}

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}



