package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.estimateTokens

internal fun validateCompressionSummary(summary: String, sourceTokens: Int) {
    val summaryTokens = UIMessage.user(summary).estimateTokens()
    check(summary.isNotBlank() && summaryTokens < sourceTokens) {
        "摘要未缩短上下文（原约 $sourceTokens Token，摘要约 $summaryTokens Token），原记录已保留。请降低摘要目标或调整压缩要求。"
    }
}

/** 目标是上限而不是需要凑满的篇幅，短历史也必须留出实际缩短的空间。 */
internal fun compressionSummaryTarget(requested: Int, sourceTokens: Int): Int {
    require(sourceTokens > 8) { "可压缩历史太短，无需压缩，原记录已保留" }
    return minOf(requested.coerceAtLeast(1), (sourceTokens - 4) / 2)
}

/** 只在发送前确定预算不够时切换备用路径，不因含糊网络失败重复请求。 */
internal fun canUseSingleCompressionRequest(
    inputTokens: Int,
    toolTokens: Long,
    instructionTokens: Int,
    reservedOutput: Int,
    contextWindow: Int,
): Boolean = contextWindow > 0 && inputTokens >= 0 && instructionTokens >= 0 && reservedOutput >= 0 &&
    toolTokens in 0..contextWindow.toLong() &&
    inputTokens.toLong() + toolTokens + instructionTokens + reservedOutput + 512 <= contextWindow
