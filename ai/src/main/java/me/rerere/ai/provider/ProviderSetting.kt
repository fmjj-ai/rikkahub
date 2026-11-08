package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val balanceOption: BalanceOption

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        balanceOption: BalanceOption = this.balanceOption,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                balanceOption = balanceOption,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                balanceOption = balanceOption
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    /**
     * 多协议服务商：一个中转站/站点下，同时保存 OpenAI、Google、Claude 三份协议配置。
     * 模型统一放在父级 [models]，每个模型通过 [Model.protocol] 选择协议。
     */
    @Serializable
    @SerialName("multi")
    data class MultiProtocol(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "New Provider",
        override var models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        /** 服务商级共享地址；三个协议都使用它。 */
        val baseUrl: String = "",
        val defaultProtocol: ProviderProtocol = ProviderProtocol.OPENAI,
        val openai: ProviderSetting.OpenAI? = null,
        val google: ProviderSetting.Google? = null,
        val claude: ProviderSetting.Claude? = null,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(from: Int, to: Int): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                balanceOption = balanceOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                openai = openai?.copy(
                    id = id,
                    enabled = enabled,
                    name = name,
                    models = emptyList(),
                    balanceOption = balanceOption,
                    builtIn = false,
                    description = {},
                    shortDescription = {},
                ),
                google = google?.copy(
                    id = id,
                    enabled = enabled,
                    name = name,
                    models = emptyList(),
                    balanceOption = balanceOption,
                    builtIn = false,
                    description = {},
                    shortDescription = {},
                ),
                claude = claude?.copy(
                    id = id,
                    enabled = enabled,
                    name = name,
                    models = emptyList(),
                    balanceOption = balanceOption,
                    builtIn = false,
                    description = {},
                    shortDescription = {},
                ),
            )
        }

        fun endpointConfig(protocol: ProviderProtocol): ProviderSetting? = when (protocol) {
            ProviderProtocol.OPENAI -> openai
            ProviderProtocol.GOOGLE -> google
            ProviderProtocol.CLAUDE -> claude
        }

        /** 取某个协议的 typed 配置（带父级 id/name/models/URL），未配置返回 null。 */
        fun endpointOrNull(protocol: ProviderProtocol): ProviderSetting? {
            return endpointConfig(protocol)?.asEndpointOf(this)
        }

        fun hasEndpoint(protocol: ProviderProtocol): Boolean = endpointConfig(protocol) != null

        fun configuredProtocols(): List<ProviderProtocol> {
            return ProviderProtocol.entries.filter { endpointOrNull(it)?.isConfigured() == true }
        }

        /** 更新某个协议的配置；用于配置页保存。 */
        fun withEndpoint(protocol: ProviderProtocol, setting: ProviderSetting?): MultiProtocol {
            return when (protocol) {
                ProviderProtocol.OPENAI -> copy(openai = setting as? ProviderSetting.OpenAI)
                ProviderProtocol.GOOGLE -> copy(google = setting as? ProviderSetting.Google)
                ProviderProtocol.CLAUDE -> copy(claude = setting as? ProviderSetting.Claude)
            }
        }
    }

    /** 账号类地址由实现固定，不能转成可填写第三方地址的 API Key 配置。 */
    @Serializable
    sealed class Account : ProviderSetting() {
        abstract val credential: OAuthCredential?
        abstract fun withCredential(credential: OAuthCredential?): Account
    }

    @Serializable
    @SerialName("chatgpt")
    data class ChatGPT(
        override val id: Uuid = Uuid.random(),
        override val enabled: Boolean = true,
        override val name: String = "ChatGPT",
        override val models: List<Model> = emptyList(),
        override val balanceOption: BalanceOption = BalanceOption(),
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        override val credential: OAuthCredential? = null,
    ) : Account() {
        override fun withCredential(credential: OAuthCredential?): Account = copy(credential = credential)
        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)
        override fun editModel(model: Model): ProviderSetting =
            copy(models = models.map { if (it.id == model.id) model else it })
        override fun delModel(model: Model): ProviderSetting = copy(models = models.filterNot { it.id == model.id })
        override fun moveMove(from: Int, to: Int): ProviderSetting =
            copy(models = models.toMutableList().apply { add(to, removeAt(from)) })

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            balanceOption: BalanceOption,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id, enabled = enabled, name = name, models = models, balanceOption = balanceOption,
            builtIn = builtIn, description = description, shortDescription = shortDescription,
        )
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}

private fun ProviderSetting.asEndpointOf(parent: ProviderSetting.MultiProtocol): ProviderSetting {
    return when (this) {
        is ProviderSetting.OpenAI -> copy(
            id = parent.id,
            enabled = parent.enabled,
            name = parent.name,
            models = parent.models,
            balanceOption = parent.balanceOption,
            builtIn = false,
            description = {},
            shortDescription = {},
            baseUrl = parent.baseUrl.ifBlank { baseUrl },
        )

        is ProviderSetting.Google -> copy(
            id = parent.id,
            enabled = parent.enabled,
            name = parent.name,
            models = parent.models,
            balanceOption = parent.balanceOption,
            builtIn = false,
            description = {},
            shortDescription = {},
            baseUrl = parent.baseUrl.ifBlank { baseUrl },
        )

        is ProviderSetting.Claude -> copy(
            id = parent.id,
            enabled = parent.enabled,
            name = parent.name,
            models = parent.models,
            balanceOption = parent.balanceOption,
            builtIn = false,
            description = {},
            shortDescription = {},
            baseUrl = parent.baseUrl.ifBlank { baseUrl },
        )

        is ProviderSetting.MultiProtocol -> this
        is ProviderSetting.Account -> error("账号提供商不支持多协议端点")
    }
}

/** 协议配置是否已经可以发请求：Key 已填（Google 也接受 Vertex Service Account）。 */
fun ProviderSetting.isConfigured(): Boolean = when (this) {
    is ProviderSetting.Account -> !credential?.accessToken.isNullOrBlank()
    is ProviderSetting.OpenAI -> apiKey.isNotBlank() && baseUrl.isNotBlank()
    is ProviderSetting.Claude -> apiKey.isNotBlank() && baseUrl.isNotBlank()
    is ProviderSetting.Google -> (apiKey.isNotBlank() && baseUrl.isNotBlank()) ||
        (vertexAI && useServiceAccount && projectId.isNotBlank() &&
            serviceAccountEmail.isNotBlank() && privateKey.isNotBlank())

    is ProviderSetting.MultiProtocol -> configuredProtocols().isNotEmpty()
}

/** 模型实际使用的协议；旧 typed 服务商直接由其类型决定。 */
fun ProviderSetting.protocolOf(model: Model): ProviderProtocol? = when (this) {
    is ProviderSetting.Account -> null
    is ProviderSetting.MultiProtocol -> model.protocol ?: defaultProtocol
    is ProviderSetting.OpenAI -> ProviderProtocol.OPENAI
    is ProviderSetting.Google -> ProviderProtocol.GOOGLE
    is ProviderSetting.Claude -> ProviderProtocol.CLAUDE
}

/**
 * 把旧的单协议服务商升级为多协议服务商，已有模型显式写入原协议，保证升级后行为不变。
 */
fun ProviderSetting.toMultiProtocol(): ProviderSetting.MultiProtocol = when (this) {
    is ProviderSetting.Account -> error("账号提供商不能转换为多协议提供商")
    is ProviderSetting.MultiProtocol -> this

    is ProviderSetting.OpenAI -> ProviderSetting.MultiProtocol(
        id = id,
        enabled = enabled,
        name = name,
        models = models.withExplicitProtocol(ProviderProtocol.OPENAI),
        balanceOption = balanceOption,
        builtIn = builtIn,
        description = description,
        shortDescription = shortDescription,
        baseUrl = baseUrl,
        defaultProtocol = ProviderProtocol.OPENAI,
        openai = copy(
            models = emptyList(),
            balanceOption = balanceOption,
            builtIn = false,
            description = {},
            shortDescription = {},
        ),
    )

    is ProviderSetting.Google -> ProviderSetting.MultiProtocol(
        id = id,
        enabled = enabled,
        name = name,
        models = models.withExplicitProtocol(ProviderProtocol.GOOGLE),
        balanceOption = balanceOption,
        builtIn = builtIn,
        description = description,
        shortDescription = shortDescription,
        baseUrl = baseUrl,
        defaultProtocol = ProviderProtocol.GOOGLE,
        google = copy(
            models = emptyList(),
            balanceOption = balanceOption,
            builtIn = false,
            description = {},
            shortDescription = {},
        ),
    )

    is ProviderSetting.Claude -> ProviderSetting.MultiProtocol(
        id = id,
        enabled = enabled,
        name = name,
        models = models.withExplicitProtocol(ProviderProtocol.CLAUDE),
        balanceOption = balanceOption,
        builtIn = builtIn,
        description = description,
        shortDescription = shortDescription,
        baseUrl = baseUrl,
        defaultProtocol = ProviderProtocol.CLAUDE,
        claude = copy(
            models = emptyList(),
            balanceOption = balanceOption,
            builtIn = false,
            description = {},
            shortDescription = {},
        ),
    )
}

private fun List<Model>.withExplicitProtocol(protocol: ProviderProtocol): List<Model> {
    return map { model -> if (model.protocol == null) model.copy(protocol = protocol) else model }
}

/** 更新服务商级共享地址，同时同步到已有协议配置。 */
fun ProviderSetting.MultiProtocol.withBaseUrl(url: String): ProviderSetting.MultiProtocol {
    return copy(
        baseUrl = url,
        openai = openai?.copy(baseUrl = url),
        google = google?.copy(baseUrl = url),
        claude = claude?.copy(baseUrl = url),
    )
}

/** 手动添加模型时的默认协议：优先第一个已配置协议，其次 defaultProtocol。 */
fun ProviderSetting.MultiProtocol.effectiveDefaultProtocol(): ProviderProtocol {
    return configuredProtocols().firstOrNull() ?: defaultProtocol
}
