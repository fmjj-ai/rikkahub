package me.rerere.rikkahub.data.ai.transformers

import kotlin.time.Instant
import me.rerere.rikkahub.utils.mapPreservingIdentity
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

private val THINKING_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CLOSING_TAG_REGEX = Regex("</think>")

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override val supportsSingleMessageProjection: Boolean = true

    override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val now = Clock.System.now()
        return messages.mapPreservingIdentity { projectThinkTags(it, now, complete = false) }
    }

    override suspend fun onGenerationFinish(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val now = Clock.System.now()
        return messages.mapPreservingIdentity { projectThinkTags(it, now, complete = true) }
    }
}

internal fun projectThinkTags(message: UIMessage, now: Instant, complete: Boolean): UIMessage {
    if (message.role != MessageRole.ASSISTANT) return message
    var updatedParts: MutableList<UIMessagePart>? = null
    message.parts.forEachIndexed { index, part ->
        val match = if (part is UIMessagePart.Text) THINKING_REGEX.find(part.text) else null
        if (match == null || part !is UIMessagePart.Text) {
            updatedParts?.add(part)
        } else {
            if (updatedParts == null) updatedParts = message.parts.take(index).toMutableList()
            updatedParts!!.add(
                UIMessagePart.Reasoning(
                    reasoning = match.groupValues.getOrNull(1)?.trim().orEmpty(),
                    createdAt = message.createdAt.toInstant(TimeZone.currentSystemDefault()),
                    finishedAt = if (complete || CLOSING_TAG_REGEX.containsMatchIn(part.text)) now else null,
                )
            )
            updatedParts!!.add(part.copy(text = part.text.replace(THINKING_REGEX, "")))
        }
    }
    return updatedParts?.let { message.copy(parts = it) } ?: message
}
