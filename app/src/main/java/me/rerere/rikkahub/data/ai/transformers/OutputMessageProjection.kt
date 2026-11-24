package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.ReferenceProjectionCache

internal data class OutputMessageSnapshot(
    val messages: List<UIMessage>,
    val visualMessages: List<UIMessage>,
)

/** 只用于同一请求步骤的展示阶段；完成变换和下一工具步骤使用各自的新基线。 */
internal class OutputMessageProjection(
    private val messageLocal: Boolean,
    private val transform: suspend (List<UIMessage>) -> List<UIMessage>,
    private val visualTransform: suspend (List<UIMessage>) -> List<UIMessage>,
) {
    private data class ProjectedMessage(val message: UIMessage, val visual: UIMessage)

    private val cache = ReferenceProjectionCache<UIMessage, ProjectedMessage> { message ->
        val transformed = transform(listOf(message)).single()
        ProjectedMessage(transformed, visualTransform(listOf(transformed)).single())
    }

    suspend fun project(messages: List<UIMessage>): OutputMessageSnapshot {
        if (!messageLocal) {
            val transformed = transform(messages)
            return OutputMessageSnapshot(transformed, visualTransform(transformed))
        }
        val projected = cache.project(messages)
        return OutputMessageSnapshot(projected.map { it.message }, projected.map { it.visual })
    }
}
