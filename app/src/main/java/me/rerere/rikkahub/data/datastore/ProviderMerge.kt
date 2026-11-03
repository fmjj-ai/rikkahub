package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderProtocol
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.effectiveDefaultProtocol
import me.rerere.ai.provider.toMultiProtocol
import me.rerere.ai.provider.withBaseUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

data class ProviderMergeOption(
    val source: ProviderSetting,
    val endpoint: ProviderSetting,
)

data class ProviderMergeConflict(
    val protocol: ProviderProtocol,
    val options: List<ProviderMergeOption>,
)

data class ProviderMergePlan(
    val primary: ProviderSetting,
    val others: List<ProviderSetting>,
    val conflicts: List<ProviderMergeConflict>,
) {
    private val participants: List<ProviderSetting> get() = listOf(primary) + others

    fun merged(selections: Map<ProviderProtocol, ProviderMergeOption> = emptyMap()): ProviderSetting.MultiProtocol {
        val baseUrl = primary.mergeBaseUrl()
        var merged = primary.toMultiProtocol().copy(
            id = primary.id,
            enabled = primary.enabled,
            name = primary.name,
            balanceOption = primary.balanceOption,
            builtIn = false,
            description = {},
            shortDescription = {},
            baseUrl = baseUrl,
            models = mergedModels(),
        )

        ProviderProtocol.entries.forEach { protocol ->
            val endpoint = selections[protocol]?.endpoint
                ?: participants.asSequence()
                    .mapNotNull { it.mergeEndpoint(protocol) }
                    .firstOrNull()
            if (endpoint != null) {
                merged = merged.withEndpoint(protocol, endpoint)
            }
        }

        return merged.copy(
            openai = merged.openai?.normalizedFor(merged) as? ProviderSetting.OpenAI,
            google = merged.google?.normalizedFor(merged) as? ProviderSetting.Google,
            claude = merged.claude?.normalizedFor(merged) as? ProviderSetting.Claude,
        ).withBaseUrl(baseUrl)
    }

    private fun mergedModels(): List<Model> {
        val result = mutableListOf<Model>()
        val seen = mutableMapOf<Uuid, Model>()
        participants.forEach { provider ->
            provider.models.forEach { model ->
                val protocol = when (provider) {
                    is ProviderSetting.MultiProtocol -> model.protocol ?: provider.effectiveDefaultProtocol()
                    else -> provider.mergeProtocol()
                }
                val resolved = model.copy(protocol = protocol)
                val existing = seen[resolved.id]
                require(existing == null || modelPayload(existing) == modelPayload(resolved)) {
                    "模型 UUID 重复且配置不同，请先为重复项重新分配 UUID，再合并提供商"
                }
                // 同名模型可能拥有独立参数及收藏/助手/历史引用，不按名称去重，保留每个 UUID。
                if (existing == null) {
                    seen[resolved.id] = resolved
                    result += resolved
                }
            }
        }
        return result
    }
}

fun buildProviderMergePlan(
    primary: ProviderSetting,
    others: List<ProviderSetting>,
): ProviderMergePlan {
    val participants = listOf(primary) + others
    val conflicts = ProviderProtocol.entries.mapNotNull { protocol ->
        val options = participants.mapNotNull { provider ->
            provider.mergeEndpoint(protocol)?.let { ProviderMergeOption(provider, it) }
        }.distinctBy { it.endpoint.requestIdentity() }
        if (options.size > 1) ProviderMergeConflict(protocol, options) else null
    }
    return ProviderMergePlan(primary, others, conflicts)
}

/** 只规范化协议、主机和默认端口，保留大小写敏感的 URL 路径。 */
fun ProviderSetting.mergeUrlIdentity(): String {
    val value = mergeBaseUrl().trim().trimEnd('/')
    return value.toHttpUrlOrNull()?.toString()?.trimEnd('/') ?: value
}

private fun modelPayload(model: Model) = JsonInstant.encodeToJsonElement(Model.serializer(), model)

/** 比较完整协议配置，不能只看密钥和地址而忽略 Responses、路径或思考/缓存策略。 */
private fun ProviderSetting.requestIdentity(): JsonObject {
    val ignored = setOf("id", "name", "enabled", "models", "balanceOption")
    val json = JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), this).jsonObject
    return JsonObject(json.filterKeys { it !in ignored })
}

/** 在最新设置上应用，旧面板不得覆盖并发修改或把已删除的提供商复活。 */
fun Settings.applyProviderMerge(
    primary: ProviderSetting,
    others: List<ProviderSetting>,
    merged: ProviderSetting.MultiProtocol,
): Settings {
    val participants = listOf(primary) + others
    require(merged.id == primary.id) { "合并主提供商 UUID 不匹配" }
    require(participants.distinctBy { it.id }.size == participants.size) { "合并参与项重复" }
    require(merged.models.map { it.id }.toSet().containsAll(participants.flatMap { it.models }.map { it.id })) {
        "合并结果缺少原模型 UUID，已阻止可能丢失引用的修改"
    }
    val currentPrimary = providers.singleOrNull { it.id == primary.id }
    val removed = others.map { it.id }.toSet()
    // 写盘失败时内存已接受合并；仅完全相同且所有副项都已移除的结果允许幂等重试。
    if (currentPrimary != null && providers.none { it.id in removed } &&
        JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), currentPrimary) ==
        JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), merged)
    ) return this
    participants.forEach { original ->
        val current = providers.find { it.id == original.id } ?: error("提供商已删除，请重新打开合并面板")
        require(JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), current) ==
            JsonInstant.encodeToJsonElement(ProviderSetting.serializer(), original)) {
            "提供商已在其他位置修改，请重新打开合并面板"
        }
    }
    return copy(providers = providers.map { if (it.id == primary.id) merged else it }.filter { it.id !in removed })
}

fun ProviderSetting.mergeBaseUrl(): String = when (this) {
    is ProviderSetting.MultiProtocol -> baseUrl.ifBlank {
        openai?.baseUrl ?: google?.baseUrl ?: claude?.baseUrl ?: ""
    }

    is ProviderSetting.OpenAI -> baseUrl
    is ProviderSetting.Google -> baseUrl
    is ProviderSetting.Claude -> baseUrl
}

private fun ProviderSetting.mergeEndpoint(protocol: ProviderProtocol): ProviderSetting? = when (this) {
    is ProviderSetting.MultiProtocol -> endpointConfig(protocol)
    is ProviderSetting.OpenAI -> takeIf { protocol == ProviderProtocol.OPENAI }
    is ProviderSetting.Google -> takeIf { protocol == ProviderProtocol.GOOGLE }
    is ProviderSetting.Claude -> takeIf { protocol == ProviderProtocol.CLAUDE }
}

private fun ProviderSetting.mergeProtocol(): ProviderProtocol? = when (this) {
    is ProviderSetting.MultiProtocol -> defaultProtocol
    is ProviderSetting.OpenAI -> ProviderProtocol.OPENAI
    is ProviderSetting.Google -> ProviderProtocol.GOOGLE
    is ProviderSetting.Claude -> ProviderProtocol.CLAUDE
}

private fun ProviderSetting.mergeApiKey(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
    is ProviderSetting.MultiProtocol -> ""
}

private fun ProviderSetting.normalizedFor(parent: ProviderSetting.MultiProtocol): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(
        id = parent.id,
        name = parent.name,
        models = emptyList(),
        balanceOption = parent.balanceOption,
        builtIn = false,
        description = {},
        shortDescription = {},
        baseUrl = parent.baseUrl.ifBlank { baseUrl },
    )

    is ProviderSetting.Google -> copy(
        id = parent.id,
        name = parent.name,
        models = emptyList(),
        balanceOption = parent.balanceOption,
        builtIn = false,
        description = {},
        shortDescription = {},
        baseUrl = parent.baseUrl.ifBlank { baseUrl },
    )

    is ProviderSetting.Claude -> copy(
        id = parent.id,
        name = parent.name,
        models = emptyList(),
        balanceOption = parent.balanceOption,
        builtIn = false,
        description = {},
        shortDescription = {},
        baseUrl = parent.baseUrl.ifBlank { baseUrl },
    )

    is ProviderSetting.MultiProtocol -> this
}
