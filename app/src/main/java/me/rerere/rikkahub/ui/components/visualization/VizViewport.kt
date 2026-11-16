package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/** 等比缩放 scene；窗口改变时保留中心和短边代表的世界范围。 */
@Stable
internal class VizViewportState {
    var zoom by mutableFloatStateOf(1f)
        private set
    var centerX by mutableFloatStateOf(0f)
        private set
    var centerY by mutableFloatStateOf(0f)
        private set
    var adjusted by mutableStateOf(false)
        private set
    private var previousSize = IntSize.Zero
    private var previousWidth = 0f
    private var previousHeight = 0f

    fun resize(sceneWidth: Float, sceneHeight: Float, canvasSize: IntSize) {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        if (adjusted && previousSize.width > 0 && previousSize.height > 0) {
            val oldScale = fitScale(previousWidth, previousHeight, previousSize) * zoom
            val worldSpan = min(previousSize.width, previousSize.height) / oldScale
            zoom = (min(canvasSize.width, canvasSize.height) / worldSpan /
                fitScale(sceneWidth, sceneHeight, canvasSize)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        }
        previousSize = canvasSize
        previousWidth = sceneWidth
        previousHeight = sceneHeight
        if (!adjusted) {
            centerX = sceneWidth / 2f
            centerY = sceneHeight / 2f
        }
        clampCenter(sceneWidth, sceneHeight, canvasSize)
    }

    /** 返回实际消耗的平移像素，边界剩余位移可交还页面滚动。 */
    fun transform(
        sceneWidth: Float,
        sceneHeight: Float,
        canvasSize: IntSize,
        centroid: Offset,
        pan: Offset,
        gestureZoom: Float,
    ): Offset {
        if (canvasSize.width <= 0 || canvasSize.height <= 0 ||
            !gestureZoom.isFinite() || gestureZoom <= 0f
        ) return Offset.Zero
        val fit = fitScale(sceneWidth, sceneHeight, canvasSize)
        if (fit <= 0f || !fit.isFinite()) return Offset.Zero
        if (!adjusted && gestureZoom == 1f) return Offset.Zero
        if (!adjusted) {
            centerX = sceneWidth / 2f
            centerY = sceneHeight / 2f
        }
        val oldScale = fit * zoom
        val anchorX = centerX + (centroid.x - canvasSize.width / 2f) / oldScale
        val anchorY = centerY + (centroid.y - canvasSize.height / 2f) / oldScale
        zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newScale = fit * zoom
        centerX = anchorX - (centroid.x - canvasSize.width / 2f) / newScale
        centerY = anchorY - (centroid.y - canvasSize.height / 2f) / newScale
        adjusted = true
        clampCenter(sceneWidth, sceneHeight, canvasSize)
        val beforePan = Offset(centerX, centerY)
        centerX -= pan.x / newScale
        centerY -= pan.y / newScale
        clampCenter(sceneWidth, sceneHeight, canvasSize)
        return Offset((beforePan.x - centerX) * newScale, (beforePan.y - centerY) * newScale)
    }

    fun zoomBy(factor: Float, sceneWidth: Float, sceneHeight: Float, canvasSize: IntSize) = transform(
        sceneWidth, sceneHeight, canvasSize,
        Offset(canvasSize.width / 2f, canvasSize.height / 2f), Offset.Zero, factor,
    )

    fun reset() {
        zoom = 1f
        adjusted = false
        centerX = previousWidth / 2f
        centerY = previousHeight / 2f
    }

    fun scale(sceneWidth: Float, sceneHeight: Float, canvasSize: IntSize): Float =
        fitScale(sceneWidth, sceneHeight, canvasSize) * zoom

    fun offset(sceneWidth: Float, sceneHeight: Float, canvasSize: IntSize): Offset {
        val scale = scale(sceneWidth, sceneHeight, canvasSize)
        val x = if (adjusted) centerX else sceneWidth / 2f
        val y = if (adjusted) centerY else sceneHeight / 2f
        return Offset(canvasSize.width / 2f - x * scale, canvasSize.height / 2f - y * scale)
    }

    private fun clampCenter(sceneWidth: Float, sceneHeight: Float, canvasSize: IntSize) {
        val scale = scale(sceneWidth, sceneHeight, canvasSize)
        if (scale <= 0f) return
        val halfWidth = canvasSize.width / (2f * scale)
        val halfHeight = canvasSize.height / (2f * scale)
        centerX = if (halfWidth >= sceneWidth / 2f) sceneWidth / 2f
        else centerX.coerceIn(halfWidth, sceneWidth - halfWidth)
        centerY = if (halfHeight >= sceneHeight / 2f) sceneHeight / 2f
        else centerY.coerceIn(halfHeight, sceneHeight - halfHeight)
    }

    private fun fitScale(sceneWidth: Float, sceneHeight: Float, canvasSize: IntSize): Float {
        if (sceneWidth <= 0f || sceneHeight <= 0f) return 0f
        return min(canvasSize.width / sceneWidth, canvasSize.height / sceneHeight)
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 8f
    }
}
