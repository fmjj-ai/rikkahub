package me.rerere.rikkahub.data.ai

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
