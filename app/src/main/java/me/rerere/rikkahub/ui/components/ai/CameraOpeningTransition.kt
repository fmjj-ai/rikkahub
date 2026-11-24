package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal const val CAMERA_OPENING_MILLIS = 360
internal const val CAMERA_CLOSING_MILLIS = 300

internal fun cameraTransitionDuration(progress: Float, visible: Boolean): Int {
    val distance = if (visible) 1f - progress else progress
    return (distance.coerceIn(0f, 1f) * if (visible) CAMERA_OPENING_MILLIS else CAMERA_CLOSING_MILLIS).roundToInt()
}

internal fun cameraOpeningBounds(source: Rect, target: Rect, progress: Float): Rect {
    val validSource = source.width > 0f && source.height > 0f &&
        source.left.isFinite() && source.top.isFinite() && source.right.isFinite() && source.bottom.isFinite()
    // 权限弹窗或尚未测量导致起点不可用时，从最终面板底部展开，不从窗口左上角飞入。
    val start = if (validSource) source else Rect(
        target.center.x - target.width * 0.12f,
        target.bottom - target.height * 0.24f,
        target.center.x + target.width * 0.12f,
        target.bottom,
    )
    return cameraAttachmentBounds(start, target, progress)
}

@Composable
internal fun CameraOpeningTransition(
    source: Rect,
    visible: Boolean = true,
    onClosed: () -> Unit = {},
    onProgress: (Float) -> Unit = {},
    readyToClose: Boolean = true,
    stationaryPreview: @Composable (atRest: Boolean) -> Unit = {},
    content: @Composable (atRest: Boolean) -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val latestOnClosed by rememberUpdatedState(onClosed)
    val latestOnProgress by rememberUpdatedState(onProgress)
    var target by remember { mutableStateOf(Rect.Zero) }
    val measured = target.width > 0f && target.height > 0f
    val fullyExpanded by remember { derivedStateOf { progress.value >= 1f } }
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value }.collect { latestOnProgress(it) }
    }
    LaunchedEffect(measured, visible, readyToClose) {
        // 先取得返回帧再开始收缩，不能先隐藏原生预览，下一帧才补上静态画面。
        if (!visible && !readyToClose) return@LaunchedEffect
        if (measured) {
            // 快速返回时从当前帧反向收缩，不跳到完整展开状态再关闭。
            progress.animateTo(
                if (visible) 1f else 0f,
                tween(cameraTransitionDuration(progress.value, visible), easing = FastOutSlowInEasing),
            )
        }
        latestOnProgress(progress.value)
        if (!visible) latestOnClosed()
    }

    // 外层只测量最终位置；在内层变换，避免把动画中的位置反写成下一帧的终点。
    Box(Modifier.fillMaxWidth().onGloballyPositioned { target = it.boundsInWindow() }) {
        // 原生 TextureView 只在最终位置绘制，绝不进入缩放/位移/淡出的图层。
        // matchParentSize 不参与外层尺寸计算，最终尺寸仍由下方的 Compose 相机面板决定。
        Box(Modifier.matchParentSize()) { stationaryPreview(measured && fullyExpanded) }
        Box(Modifier.fillMaxWidth().graphicsLayer {
            alpha = when {
                !measured -> 0f
                visible || !readyToClose -> 1f
                else -> cameraClosingAlpha(progress.value)
            }
            if (measured) {
                val bounds = cameraOpeningBounds(source, target, progress.value)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = bounds.width / target.width
                scaleY = bounds.height / target.height
                // 起点、终点均为窗口坐标，这里仅转换为面板局部位移；平板不叠加聊天区偏移。
                translationX = bounds.left - target.left
                translationY = bounds.top - target.top
            }
            shape = RoundedCornerShape(32.dp)
            clip = true
        }) {
            content(measured && fullyExpanded)
        }
    }
}
