package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import me.rerere.rikkahub.data.visualization.VizFence

@Composable
internal fun VisualizationQuotaNotice(fence: VizFence) {
    var sourceVisible by remember(fence.ordinal) { mutableStateOf(false) }
    Column {
        Text("已达每条消息四个交互图块的上限")
        TextButton(onClick = { sourceVisible = !sourceVisible }) {
            Text(if (sourceVisible) "收起源码" else "查看源码")
        }
        if (sourceVisible) Text(fence.code)
    }
}
