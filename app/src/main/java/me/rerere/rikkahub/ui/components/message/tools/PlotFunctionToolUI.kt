package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.graph.GraphError
import me.rerere.rikkahub.data.graph.GraphProtocol
import me.rerere.rikkahub.data.graph.GraphResult
import me.rerere.rikkahub.data.graph.PLOT_FUNCTION_TOOL_NAME
import me.rerere.rikkahub.ui.components.graph.FunctionGraphCard

object PlotFunctionToolUI : ToolUIRenderer {
    override val toolName: String = PLOT_FUNCTION_TOOL_NAME

    @Composable
    override fun title(context: ToolUIContext): String =
        when {
            context.loading -> "正在创建函数图像…"
            context.content?.getStringContent("ok") == "false" -> "函数绘图失败"
            context.tool.isExecuted -> "函数绘图完成"
            else -> "函数绘图"
        }

    // 正文负责图卡，步骤仅保留追踪信息；详情页为用户主动打开时的独立查看入口。
    override fun hasSummary(context: ToolUIContext): Boolean = false

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        DefaultToolPreview(context)
    }
}

@Composable
fun PlotFunctionToolContent(tool: UIMessagePart.Tool, loading: Boolean) {
    val text = remember(tool.output) { tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text } }
    val result by produceState<GraphResult?>(null, text) {
        value = if (text.isBlank()) null else withContext(Dispatchers.Default) {
            try {
                GraphProtocol.decodeResult(text)
            } catch (_: IllegalArgumentException) {
                GraphResult(false, error = GraphError("INVALID_HISTORY", "此绘图记录无效或版本不受支持，可在工具详情查看原始数据"))
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            result?.ok == true -> result!!.graphs.forEachIndexed { index, graph ->
                key("${tool.toolCallId}:graph:$index") { FunctionGraphCard(graph) }
            }
            result != null -> Text(
                result!!.error?.message ?: "绘图失败，请检查表达式与视窗参数",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            loading -> Text("正在创建函数图像…", style = MaterialTheme.typography.bodySmall)
            text.isNotBlank() -> Text("正在读取绘图记录…", style = MaterialTheme.typography.bodySmall)
            else -> Text("绘图尚未完成，可从工具步骤查看状态", style = MaterialTheme.typography.bodySmall)
        }
    }
}
