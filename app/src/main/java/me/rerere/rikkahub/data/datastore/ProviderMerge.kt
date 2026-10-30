package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderProtocol
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.effectiveDefaultProtocol
import me.rerere.ai.provider.toMultiProtocol
import me.rerere.ai.provider.withBaseUrl

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
        val seen = mutableSetOf<Pair<String, ProviderProtocol?>>()
        participants.forEach { provider ->
            provider.models.forEach { model ->
                val protocol = when (provider) {
                    is ProviderSetting.MultiProtocol -> model.protocol ?: provider.effectiveDefaultProtocol()
                    else -> provider.mergeProtocol()
                }
                if (seen.add(model.modelId to protocol)) {
                    result += model.copy(protocol = protocol)
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
        }.distinctBy { it.endpoint.mergeApiKey() to it.endpoint.mergeBaseUrl() }
        if (options.size > 1) ProviderMergeConflict(protocol, options) else null
    }
    return ProviderMergePlan(primary, others, conflicts)
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
