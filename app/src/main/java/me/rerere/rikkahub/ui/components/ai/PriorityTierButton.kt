package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ZapFilledIcon
import me.rerere.rikkahub.ui.components.ui.icons.ZapOutlineIcon

/**
 * 输入栏「快速模式 / 优先级模式」开关。
 *
 * - 常驻显示：不因当前模型是否受支持而隐藏。
 * - 单击即切换，无二次确认、无 Toast。
 * - 关闭态为描边闪电，开启态为同路径实心填充，颜色由 [ToggleSurface] 决定
 *   （开启 = 主题 primary，关闭 = onSurface），与「联网搜索 / 推理等级」的激活态风格一致。
 */
@Composable
fun PriorityTierButton(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ToggleSurface(
        checked = enabled,
        onClick = { onToggle(!enabled) },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (enabled) ZapFilledIcon else ZapOutlineIcon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
