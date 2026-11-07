package me.rerere.rikkahub.ui.components.ai

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** 转场缓存不影响拍照原图；最长边限制为 1024px，单帧最多约 4MiB，作为复制失败时的兜底。 */
internal fun cameraPreviewFrameSize(width: Int, height: Int): IntSize {
    if (width <= 0 || height <= 0) return IntSize.Zero
    val ratio = minOf(1f, 1024f / maxOf(width, height))
    return IntSize((width * ratio).roundToInt().coerceIn(1, 1024), (height * ratio).roundToInt().coerceIn(1, 1024))
}

/** 准备返回帧时仍显示原生预览；帧就绪后只动画 Compose 图像，不变换原生视图。 */
internal fun useStationaryCameraPreview(
    streaming: Boolean,
    atRest: Boolean,
    visible: Boolean,
    closingFrameReady: Boolean,
): Boolean = streaming && atRest && (visible || !closingFrameReady)

internal fun Modifier.stationaryCameraPreview(showPreview: Boolean, initializing: Boolean): Modifier =
    drawWithContent {
        // 初始化时允许建立纹理；流就绪后，动画期间不提交原生视图的绘制指令。
        if (showPreview || initializing) drawContent()
    }.graphicsLayer { alpha = if (showPreview) 1f else 0f }
