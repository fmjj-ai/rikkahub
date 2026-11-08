package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.mapPreservingIdentity
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override val supportsSingleMessageProjection: Boolean = true

    override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        messages.mapPreservingIdentity { projectAssistantRegexes(it, ctx.assistant) }
}

internal fun projectAssistantRegexes(message: UIMessage, assistant: Assistant): UIMessage {
    if (assistant.regexes.isEmpty() || message.role != MessageRole.ASSISTANT) return message
    val parts = message.parts.mapPreservingIdentity { part ->
        when (part) {
            is UIMessagePart.Text -> {
                val text = part.text.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false)
                if (text == part.text) part else part.copy(text = text)
            }
            is UIMessagePart.Reasoning -> {
                val text = part.reasoning.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT, visual = false)
                if (text == part.reasoning) part else part.copy(reasoning = text)
            }
            else -> part
        }
    }
    return if (parts === message.parts) message else message.copy(parts = parts)
}
