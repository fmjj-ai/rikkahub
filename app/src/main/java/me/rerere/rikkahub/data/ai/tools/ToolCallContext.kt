package me.rerere.rikkahub.data.ai.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 工具执行期间携带的调用信息。
 *
 * [me.rerere.ai.core.Tool.execute] 只接收入参 JSON，拿不到 toolCallId；
 * 而"生成中预览"需要按 toolCallId 定位到具体那一步工具调用。
 * 因此 [me.rerere.rikkahub.data.ai.GenerationHandler] 在执行工具时把该元素放进协程上下文，
 * 需要关联信息的工具（如生图）用 `currentCoroutineContext()[ToolCallContext]?.toolCallId` 取出即可，
 * 其他工具完全不受影响。
 */
class ToolCallContext(
    val toolCallId: String,
    val messages: List<me.rerere.ai.ui.UIMessage> = emptyList(),
) : AbstractCoroutineContextElement(ToolCallContext) {
    companion object Key : CoroutineContext.Key<ToolCallContext>
}
