package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.util.json
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** 摘要挂在被压缩区间的最后一条消息上，原消息和分支仍完整保留。 */
@Serializable
data class ContextCompaction(
    val summary: String,
    val prefixHash: String,
    val summaryId: Uuid = Uuid.random(),
)

/** 不包含 usage、翻译和摘要，编辑内容或切换分支后旧摘要会自动失效。 */
fun List<UIMessage>.contextFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (message in this) {
        digest.update(message.id.toString().toByteArray())
        digest.update(message.role.name.toByteArray())
        digest.update(json.encodeToString(message.parts).toByteArray())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun List<UIMessage>.contextMessages(): List<UIMessage> {
    for (index in indices.reversed()) {
        val checkpoint = this[index]
        if (checkpoint.isContextCheckpoint) {
            if (checkpoint.toText().isNotBlank() && checkpoint.contextCheckpointHash != null &&
                subList(0, index).contextFingerprint() == checkpoint.contextCheckpointHash
            ) {
                return listOf(checkpoint) + drop(index + 1).filterNot { it.isContextCheckpoint }
            }
            continue
        }
        val compaction = this[index].contextCompaction ?: continue
        if (compaction.summary.isBlank()) continue
        if (subList(0, index + 1).contextFingerprint() == compaction.prefixHash) {
            return listOf(UIMessage.user(compaction.summary).copy(
                id = compaction.summaryId,
                isContextCheckpoint = true,
            )) + drop(index + 1).filterNot { it.isContextCheckpoint }
        }
    }
    // 无效检查点仍可在时间线查看，但不把过期摘要当作真实用户指令发送。
    return filterNot { it.isContextCheckpoint }
}

/** 只压缩完整的早期轮次，最后一轮用户消息及其全部工具调用始终保留。 */
fun List<UIMessage>.compressionBoundary(keepRecentMessages: Int = 4): Int? {
    val lastUserIndex = indexOfLast { it.role == MessageRole.USER }
    if (lastUserIndex <= 0) return null
    val requestedStart = (size - keepRecentMessages.coerceAtLeast(1)).coerceAtLeast(1)
    val keepStart = (minOf(requestedStart, lastUserIndex) downTo 1)
        .firstOrNull { this[it].role == MessageRole.USER } ?: lastUserIndex
    return keepStart - 1
}

/** 返回原始记录中的压缩终点，避免反复压缩只有摘要、没有新历史的上下文。 */
fun List<UIMessage>.compressionTarget(keepRecentMessages: Int = 4): Int? {
    val active = contextMessages()
    if (keepRecentMessages <= 0) {
        val last = active.lastOrNull { !it.isContextCheckpoint } ?: return null
        return indexOfFirst { it.id == last.id }.takeIf { it >= 0 }
    }
    var boundary = active.compressionBoundary(keepRecentMessages) ?: return null
    if (none { it.id == active[boundary].id }) {
        boundary = active.indexOfLast { it.role == MessageRole.USER } - 1
    }
    if (active.take(boundary + 1).none { !it.isContextCheckpoint }) return null
    val id = active.getOrNull(boundary)?.id ?: return null
    return indexOfFirst { it.id == id }.takeIf { it >= 0 }
}

/** 用于阈值判断的本地估算；供应商未返回 usage 时也能正常工作。 */
fun String.estimateTokens(): Int {
    var units = 0L
    var index = 0
    while (index < length) {
        val codePoint = Character.codePointAt(this, index)
        units += if (codePoint <= 127) 1 else 4
        index += Character.charCount(codePoint)
    }
    return ((units + 3) / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

fun UIMessagePart.estimateTokens(): Int = when (this) {
    is UIMessagePart.Text -> text.estimateTokens()
    is UIMessagePart.Reasoning -> reasoning.estimateTokens()
    is UIMessagePart.Tool -> (toolName + input).estimateTokens() + output.sumOf { it.estimateTokens() } + 16
    is UIMessagePart.ToolCall -> (toolName + arguments).estimateTokens() + 16
    is UIMessagePart.ToolResult -> content.toString().estimateTokens() + 16
    is UIMessagePart.Image, is UIMessagePart.Video, is UIMessagePart.Audio -> 1_024
    is UIMessagePart.Document -> 0 // 文档在转换器展开后按实际文本估算。
    UIMessagePart.Search -> 4
}

fun UIMessage.estimateTokens(): Int = (parts.sumOf { it.estimateTokens().toLong() } + 4)
    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

fun List<UIMessage>.estimateTokens(): Int = sumOf { it.estimateTokens().toLong() }
    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

fun UIMessage.compressionText(): String = "[${role.name}]\n" + parts.joinToString("\n") { it.compressionText() }

private fun UIMessagePart.compressionText(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Reasoning -> reasoning
    is UIMessagePart.Tool -> "[工具 $toolName]\n$input\n" + output.joinToString("\n") { it.compressionText() }
    is UIMessagePart.ToolCall -> "[工具 $toolName]\n$arguments"
    is UIMessagePart.ToolResult -> "[工具结果 $toolName]\n$content"
    is UIMessagePart.Document -> "[附件 $fileName]"
    is UIMessagePart.Image -> "[图片]"
    is UIMessagePart.Video -> "[视频]"
    is UIMessagePart.Audio -> "[音频]"
    UIMessagePart.Search -> "[搜索]"
}

/** 按 token 预算切块，保留全部内容且不会把 emoji 的代理对拆开。 */
fun String.splitForCompression(maxTokens: Int): List<String> {
    require(maxTokens > 0)
    if (isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    var start = 0
    var index = 0
    var units = 0L
    val maxUnits = maxTokens.toLong() * 4
    while (index < length) {
        val codePoint = Character.codePointAt(this, index)
        val nextUnits = if (codePoint <= 127) 1 else 4
        if (units + nextUnits > maxUnits && index > start) {
            chunks += substring(start, index)
            start = index
            units = 0
        }
        units += nextUnits
        index += Character.charCount(codePoint)
    }
    chunks += substring(start)
    return chunks
}

/** 所有分块成功且摘要确实缩短之后才返回；失败和取消直接交给调用方处理。 */
suspend fun compressContextText(
    source: String,
    inputBudget: Int,
    summarize: suspend (String) -> String,
): String {
    suspend fun summarizeChunks(text: String): List<String> = text.splitForCompression(inputBudget).map {
        summarize(it).trim().also { summary -> check(summary.isNotBlank()) { "压缩模型返回了空摘要" } }
    }
    var summaries = summarizeChunks(source)
    var rounds = 0
    while (summaries.size > 1 && rounds++ < 4) {
        summaries = summarizeChunks(summaries.joinToString("\n\n"))
    }
    val summary = summaries.joinToString("\n\n")
    check(summary.isNotBlank() && summary.estimateTokens() < source.estimateTokens()) {
        "摘要未缩短上下文，已保留原始记录。请调整压缩模型或目标 Token 数。"
    }
    return summary
}

data class ContextUsage(
    val usedTokens: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val estimated: Boolean = true,
    val canCompress: Boolean = false,
) {
    fun ratio(model: Model): Float = usedTokens.toFloat() / model.contextWindow

    /**
     * 缓存命中率 = cachedTokens / promptTokens。
     *
     * 仓库内各 provider 解析出的 promptTokens **均已包含**缓存部分
     * （OpenAI `prompt_tokens` / Responses `input_tokens`、Google `promptTokenCount`、
     * Claude `input + cache_read + cache_creation`），因此分母直接取 inputTokens。
     * 对异常数据做夹取，保证分母 ≥ 分子且结果落在 0..1；
     * 有输入但没有缓存命中时返回 0；缺少有效输入用量时返回 null。
     */
    val cacheHitRate: Float?
        get() {
            if (inputTokens <= 0) return null
            if (cachedTokens <= 0) return 0f
            val denominator = inputTokens.coerceAtLeast(cachedTokens)
            return (cachedTokens.toFloat() / denominator).coerceIn(0f, 1f)
        }
}

/** in/out 为最近一次请求；used 为当前有效上下文，绝不累计每轮重复输入。 */
fun List<UIMessage>.contextUsage(model: Model, systemPrompt: String = "", messageLimit: Int = 0): ContextUsage {
    val messages = contextMessages()
    val estimated = (messages.limitContext(messageLimit).estimateTokens().toLong() + systemPrompt.estimateTokens())
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val index = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
    val message = messages.getOrNull(index)
    val usage = message?.usage
    val input = usage?.promptTokens?.takeIf { it > 0 } ?: message?.contextInputTokens ?: estimated
    val output = usage?.completionTokens?.takeIf { it > 0 } ?: message?.estimateTokens() ?: 0
    val hasMatchingRequest = message?.contextInputHash != null && message.modelId == model.id &&
        message.contextInputHash == messages.take(index).contextFingerprint()
    val trailing = if (index >= 0) messages.drop(index + 1).estimateTokens() else 0
    return ContextUsage(
        usedTokens = if (hasMatchingRequest) {
            (input.toLong() + output + trailing).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else estimated,
        inputTokens = input,
        outputTokens = output,
        cachedTokens = usage?.cachedTokens ?: 0,
        estimated = !hasMatchingRequest || usage == null || trailing > 0,
        canCompress = compressionTarget() != null,
    )
}
