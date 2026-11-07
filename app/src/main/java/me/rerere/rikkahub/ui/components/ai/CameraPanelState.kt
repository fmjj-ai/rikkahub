package me.rerere.rikkahub.ui.components.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

internal enum class CameraCloseAction { RETURN_TO_ATTACHMENTS, DISMISS_PICKER }

internal class CameraPanelState {
    var showing by mutableStateOf(false)
        private set
    var expanded by mutableStateOf(false)
        private set
    private var dismissPicker = false
    var progress by mutableFloatStateOf(0f)
        private set

    val acceptsCapture: Boolean get() = showing && expanded
    val returningToAttachments: Boolean get() = showing && !expanded && !dismissPicker

    fun open() {
        dismissPicker = false
        progress = 0f
        showing = true
        expanded = true
    }

    fun requestClose(dismissPicker: Boolean = false): Boolean {
        if (!showing) return false
        this.dismissPicker = this.dismissPicker || dismissPicker
        val startedClosing = expanded
        expanded = false
        // 保持 showing，直到收缩结束才移除相机并解绑 CameraX。
        return startedClosing
    }

    fun finishClose(): CameraCloseAction? {
        if (!showing || expanded) return null
        val action = if (dismissPicker) CameraCloseAction.DISMISS_PICKER else CameraCloseAction.RETURN_TO_ATTACHMENTS
        dismiss()
        return action
    }

    fun updateProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
    }

    fun dismiss() {
        showing = false
        expanded = false
        dismissPicker = false
        progress = 0f
    }
}

internal fun showCameraLoadingHint(expanded: Boolean, streaming: Boolean, hasError: Boolean): Boolean =
    expanded && !streaming && !hasError

internal fun cameraClosingAlpha(progress: Float): Float = (progress * 4f).coerceIn(0f, 1f)

// 同一进度驱动双向交接；附件始终挂载在相机下方，不在返回时重建或重播入场。
internal fun cameraAttachmentsAlpha(progress: Float): Float = 1f - cameraClosingAlpha(progress)
