package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessage

/** 使用现有主题的紧凑摘要入口，不替换聊天页面的布局与气泡风格。 */
@Composable
internal fun ChatMessageContextCheckpoint(message: UIMessage, modifier: Modifier = Modifier) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("上下文摘要", style = MaterialTheme.typography.labelLarge)
            Text(
                "原始记录和分支保留 · 点击查看",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("上下文摘要") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("历史或分支被修改后，过期摘要会自动停止用于请求。\n")
                    Text(message.toText())
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } },
        )
    }
}
