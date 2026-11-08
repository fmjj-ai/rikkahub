package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.uuid.Uuid

/** 先检查变化部分；没有删除候选时，不读取其他历史消息的附件。 */
internal fun Conversation.removedFileUrlsSince(previous: Conversation): Set<String> {
    if (messageNodes === previous.messageNodes) return emptySet()
    val candidates = linkedSetOf<String>()
    var nodesById: Map<Uuid, MessageNode>? = null
    previous.messageNodes.forEachIndexed { index, oldNode ->
        val positional = messageNodes.getOrNull(index)
        val newNode = if (positional?.id == oldNode.id) positional else {
            if (nodesById == null) nodesById = messageNodes.associateBy { it.id }
            nodesById?.get(oldNode.id)
        }
        if (newNode === oldNode || newNode?.messages === oldNode.messages) return@forEachIndexed
        collectRemovedMessageFiles(oldNode.messages, newNode?.messages.orEmpty(), candidates)
    }
    if (candidates.isEmpty()) return emptySet()

    // 隐藏分支和工具的嵌套结果也能继续持有同一文件，不能只核对当前显示分支。
    for (node in messageNodes) {
        for (message in node.messages) {
            for (url in message.parts.localFileUrls()) {
                candidates.remove(url)
                if (candidates.isEmpty()) return emptySet()
            }
        }
    }
    return candidates
}

private fun collectRemovedMessageFiles(
    previous: List<UIMessage>,
    current: List<UIMessage>,
    candidates: MutableSet<String>,
) {
    var messagesById: Map<Uuid, UIMessage>? = null
    previous.forEachIndexed { index, oldMessage ->
        val positional = current.getOrNull(index)
        val newMessage = if (positional?.id == oldMessage.id) positional else {
            if (messagesById == null) messagesById = current.associateBy { it.id }
            messagesById?.get(oldMessage.id)
        }
        if (oldMessage === newMessage || oldMessage.parts === newMessage?.parts) return@forEachIndexed
        collectRemovedPartFiles(oldMessage.parts, newMessage?.parts.orEmpty(), candidates)
    }
}

private fun collectRemovedPartFiles(
    previous: List<UIMessagePart>,
    current: List<UIMessagePart>,
    candidates: MutableSet<String>,
) {
    if (previous === current) return
    val retainedParts = Collections.newSetFromMap(IdentityHashMap<UIMessagePart, Boolean>())
    retainedParts.addAll(current)
    val directUrls = current.mapNotNull { it.localFileUrl() }.toSet()
    var toolsById: Map<String, UIMessagePart.Tool>? = null
    for (part in previous) {
        if (part in retainedParts) continue
        if (part is UIMessagePart.Tool) {
            if (toolsById == null) {
                toolsById = current.filterIsInstance<UIMessagePart.Tool>().associateBy { it.toolCallId }
            }
            collectRemovedPartFiles(part.output, toolsById[part.toolCallId]?.output.orEmpty(), candidates)
        } else {
            part.localFileUrl()?.takeIf { it !in directUrls }?.let { candidates.add(it) }
        }
    }
}

private fun List<UIMessagePart>.localFileUrls(): Sequence<String> = sequence {
    for (part in this@localFileUrls) {
        part.localFileUrl()?.let { yield(it) }
        if (part is UIMessagePart.Tool) yieldAll(part.output.localFileUrls())
    }
}

private fun UIMessagePart.localFileUrl(): String? = when (this) {
    is UIMessagePart.Image -> url
    is UIMessagePart.Video -> url
    is UIMessagePart.Audio -> url
    is UIMessagePart.Document -> url
    else -> null
}?.takeIf { it.startsWith("file://") }
