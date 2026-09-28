package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ContextUsage
import me.rerere.rikkahub.ui.theme.extendColors
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
private fun contextColor(ratio: Float): Color = when {
    ratio >= 0.7f -> MaterialTheme.extendColors.red6
    ratio >= 0.5f -> MaterialTheme.extendColors.orange6
    else -> MaterialTheme.extendColors.green6
}

@Composable
fun ContextUsageButton(
    usage: ContextUsage,
    model: Model,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ratio = usage.ratio(model)
    val color = contextColor(ratio)
    Surface(
        onClick = onClick,
        modifier = modifier.size(40.dp).semantics {
            contentDescription = "上下文使用情况"
            stateDescription = "已使用 ${(ratio * 100).roundToInt()}%"
        },
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { ratio.coerceIn(0f, 1f) },
                modifier = Modifier.size(22.dp),
                color = color,
                trackColor = color.copy(alpha = 0.3f),
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
fun ContextUsageDialog(
    usage: ContextUsage,
    model: Model,
    canCompress: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCompress: () -> Unit,
) {
    var showConfirmation by remember { mutableStateOf(false) }
    val ratio = usage.ratio(model)
    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("确认立刻压缩上下文？") },
            text = {
                Text("较早的对话会被整理为摘要，用于后续聊天。原始记录和分支保留，最新一轮消息保持完整。")
            },
            confirmButton = {
                TextButton(
                    enabled = canCompress && !busy,
                    onClick = {
                        showConfirmation = false
                        onCompress()
                        onDismiss()
                    },
                ) { Text("确认压缩") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("取消") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文使用情况") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { ratio.coerceIn(0f, 1f) },
                        modifier = Modifier.size(80.dp),
                        color = contextColor(ratio),
                        trackColor = contextColor(ratio).copy(alpha = 0.18f),
                        strokeWidth = 6.dp,
                    )
                    Text("${(ratio * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                }
                UsageRow("used · 当前占用", "${formatTokens(usage.usedTokens)} / ${formatTokens(model.contextWindow)}")
                UsageRow("in · 最近输入", formatTokens(usage.inputTokens))
                UsageRow("out · 最近输出", formatTokens(usage.outputTokens))
                UsageRow("自动压缩阈值", formatTokens(model.autoCompressThreshold))
                Text(
                    if (usage.estimated) {
                        "单位：Token。当前占用为估算；附件、工具和不同模型的计数可能存在偏差。in / out 优先使用服务端用量。"
                    } else {
                        "单位：Token。当前占用包含最近一次请求的输入与输出，使用服务端返回的用量。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canCompress) {
                    Text(
                        if (busy) "请等待当前生成或压缩完成" else "暂无可压缩的历史，最新一轮会完整保留",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = canCompress && !busy, onClick = { showConfirmation = true }) {
                Text("立刻压缩")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun UsageRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatTokens(tokens: Int): String =
    NumberFormat.getIntegerInstance().format(tokens.coerceAtLeast(0))
