package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.local.ImageGenerationProgress
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.modifier.shimmer

/**
 * 画图子 agent 的专属渲染器。
 *
 * - 生成中：显示 partial 半成品预览（[ImageGenerationProgress]，由工具执行时写入），让用户看到画面逐渐成形。
 * - 完成后：由本渲染器自行展示图片输出（见 [rendersImages]），比通用缩略图行更适合看图，点击可放大。
 * - 失败：把工具返回的错误信息直接呈现给用户。
 */
object ImageGenerationToolUI : ToolUIRenderer {
    /** 生成中/完成态图片的展示上限，平板为主要目标平台 */
    private val MAX_IMAGE_SIDE = 320.dp

    override val toolName: String = IMAGE_GENERATION_TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Image02

    @Composable
    override fun title(context: ToolUIContext): String {
        val prompt = context.arguments.getStringContent("prompt").orEmpty()
        val preview = if (prompt.length > 40) prompt.take(40) + "…" else prompt
        return if (context.loading) "正在画图…$preview" else "画图：$preview"
    }

    /** 图片输出由本渲染器自行绘制，框架不再追加通用缩略图行（避免重复展示） */
    override fun rendersImages(context: ToolUIContext): Boolean =
        context.tool.output.any { it is UIMessagePart.Image }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || context.tool.output.isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val previews by ImageGenerationProgress.previews.collectAsState()
        val partialUrl = previews[context.tool.toolCallId]
        val images = context.tool.output.filterIsInstance<UIMessagePart.Image>()
        val error = context.content.getStringContent("error")

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (images.isNotEmpty()) {
                images.forEach { image ->
                    ZoomableAsyncImage(
                        model = image.url,
                        contentDescription = null,
                        modifier = Modifier
                            .heightIn(max = MAX_IMAGE_SIDE)
                            .widthIn(max = MAX_IMAGE_SIDE)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            } else if (context.loading) {
                // 边生成边刷新半成品；还没有第一张时只给一行状态文字，不占版面
                if (partialUrl != null) {
                    ZoomableAsyncImage(
                        model = partialUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .heightIn(max = MAX_IMAGE_SIDE)
                            .widthIn(max = MAX_IMAGE_SIDE)
                            .clip(RoundedCornerShape(12.dp))
                            .shimmer(isLoading = true),
                    )
                } else {
                    Text(
                        text = "正在生成图片…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }

            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
