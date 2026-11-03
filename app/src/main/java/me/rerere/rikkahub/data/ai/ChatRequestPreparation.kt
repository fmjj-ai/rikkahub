package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.contextMessages
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import kotlin.uuid.Uuid

/** 聊天与普通压缩共用参数构造，避免工具定义、自定义字段或协议路由分叉。 */
internal fun chatGenerationParams(
    assistant: Assistant,
    model: Model,
    provider: ProviderSetting,
    tools: List<Tool>,
): TextGenerationParams = TextGenerationParams(
    model = model,
    temperature = assistant.temperature,
    topP = assistant.topP,
    maxTokens = assistant.maxTokens,
    tools = tools,
    reasoningLevel = assistant.reasoningLevel,
    customHeaders = assistant.customHeaders + model.customHeaders,
    customBody = assistant.customBodies + model.customBodies,
    serviceTierPriority = assistant.enablePriorityTier && provider is ProviderSetting.OpenAI,
)

internal suspend fun prepareChatMessages(
    context: Context,
    assistant: Assistant,
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    tools: List<Tool>,
    memories: List<AssistantMemory>,
    transformers: List<MessageTransformer>,
    processingStatus: MutableStateFlow<String?>,
    conversationSystemPrompt: String?,
    conversationModeInjectionIds: Set<Uuid>,
    conversationLorebookIds: Set<Uuid>,
    workspaceCwd: String?,
): List<UIMessage> = buildList {
    val system = buildString {
        val effectiveSystemPrompt = if (
            assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()
        ) {
            conversationSystemPrompt
        } else assistant.systemPrompt
        if (effectiveSystemPrompt.isNotBlank()) append(effectiveSystemPrompt)
        if (assistant.enableMemory) {
            appendLine()
            append(buildMemoryPrompt(memories))
        }
        tools.forEach { tool ->
            appendLine()
            append(tool.systemPrompt(model, messages))
        }
    }
    if (system.isNotBlank()) add(UIMessage.system(system))
    addAll(messages.contextMessages().limitContext(assistant.contextMessageLimit))
}.transforms(
    transformers = transformers,
    context = context,
    model = model,
    assistant = assistant,
    settings = settings,
    conversationModeInjectionIds = conversationModeInjectionIds,
    conversationLorebookIds = conversationLorebookIds,
    processingStatus = processingStatus,
    workspaceCwd = workspaceCwd,
)
