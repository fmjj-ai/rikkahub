package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting

internal fun Settings.normalized(): Settings = this
    .let {
        var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
        DEFAULT_PROVIDERS.forEach { defaultProvider ->
            if (providers.none { it.id == defaultProvider.id }) {
                providers.add(defaultProvider.copyProvider())
            }
        }
        providers = providers.map { provider ->
            val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
            if (defaultProvider != null) {
                provider.copyProvider(
                    builtIn = defaultProvider.builtIn,
                    description = defaultProvider.description,
                    shortDescription = defaultProvider.shortDescription,
                )
            } else provider
        }.toMutableList()
        val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
        DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
            if (assistants.none { it.id == defaultAssistant.id }) {
                assistants.add(defaultAssistant.copy())
            }
        }
        val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
        DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
            if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                ttsProviders.add(defaultTTSProvider.copyProvider())
            }
        }
        it.copy(
            providers = providers,
            assistants = assistants.map { assistant -> assistant.withOfficialDefaultPrompt() },
            compressPrompt = officialDefaultCompressPrompt(it.compressPrompt),
            ttsProviders = ttsProviders,
        )
    }
    .let { settings ->
        // 去重并清理无效引用
        val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
        val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
        val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
        val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
        val asrProviders = settings.asrProviders.distinctBy { it.id }
        settings.copy(
            providers = settings.providers.distinctBy { it.id }.map { provider ->
                when (provider) {
                    is ProviderSetting.Account -> provider.copyProvider(
                        models = provider.models.distinctBy { model -> model.id }
                    )
                    is ProviderSetting.OpenAI -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }
                    )

                    is ProviderSetting.Google -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }
                    )

                    is ProviderSetting.Claude -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }
                    )

                    is ProviderSetting.MultiProtocol -> provider.copy(
                        models = provider.models.distinctBy { model -> model.id }
                    )
                }
            },
            assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                assistant.copy(
                    // 过滤掉不存在的 MCP 服务器 ID
                    mcpServers = assistant.mcpServers.filter { serverId ->
                        serverId in validMcpServerIds
                    }.toSet(),
                    // 过滤掉不存在的模式注入 ID
                    modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                        id in validModeInjectionIds
                    }.toSet(),
                    // 过滤掉不存在的 Lorebook ID
                    lorebookIds = assistant.lorebookIds.filter { id ->
                        id in validLorebookIds
                    }.toSet(),
                    // 过滤掉不存在的快捷消息 ID
                    quickMessageIds = assistant.quickMessageIds.filter { id ->
                        id in validQuickMessageIds
                    }.toSet()
                )
            },
            ttsProviders = settings.ttsProviders.distinctBy { it.id },
            asrProviders = asrProviders,
            selectedASRProviderId = settings.selectedASRProviderId
                ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                ?: asrProviders.firstOrNull()?.id,
            searchServiceSelected = settings.searchServiceSelected.coerceIn(
                0, (settings.searchServices.size - 1).coerceAtLeast(0),
            ),
            favoriteModels = settings.favoriteModels.filter { uuid ->
                settings.providers.flatMap { it.models }.any { it.id == uuid }
            },
            modeInjections = settings.modeInjections.distinctBy { it.id },
            lorebooks = settings.lorebooks.distinctBy { it.id },
            quickMessages = settings.quickMessages.distinctBy { it.id },
        )
    }
