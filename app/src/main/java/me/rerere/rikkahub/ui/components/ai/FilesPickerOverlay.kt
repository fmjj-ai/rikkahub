package me.rerere.rikkahub.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 附件、遮罩和相机始终位于同一窗口；返回时不创建新的 Dialog 或重新播放附件入场。 */
@Composable
internal fun FilesPickerOverlay(
    cameraPanel: CameraPanelState,
    onDismissRequest: () -> Unit,
    onCloseCamera: (dismissPicker: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    camera: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val latestOnDismiss by rememberUpdatedState(onDismissRequest)
    var dismissing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // 同窗口浮层不会像 Dialog 自动接管焦点，显式收起输入法，防止动画期间落点漂移。
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    if (sheetState.hasExpandedState) {
        LaunchedEffect(sheetState) { sheetState.show() }
    }
    val scrimAlpha by animateFloatAsState(
        targetValue = if (sheetState.targetValue == SheetValue.Hidden) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "附件遮罩",
    )
    fun dismissSheet() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            try {
                sheetState.hide()
                if (!sheetState.isVisible) latestOnDismiss()
            } finally {
                dismissing = false
            }
        }
    }

    Box(modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        // 只在整体附件浮层进出时改变遮罩，相机展开/缩回全程保持同一亮度。
        Box(Modifier.fillMaxSize().background(
            BottomSheetDefaults.ScrimColor.let { it.copy(alpha = it.alpha * scrimAlpha) },
        ).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClickLabel = "关闭附件",
        ) { dismissSheet() }.then(if (cameraPanel.showing) Modifier.clearAndSetSemantics { } else Modifier))

        FilesPickerSheetLayer(cameraPanel) {
            // 安全区、输入法和面板内部的放置偏移全部留在全窗口合成层内部。
            Box(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .imePadding(),
            ) {
                BottomSheet(
                    state = sheetState,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .then(if (cameraPanel.showing) Modifier.clearAndSetSemantics { } else Modifier),
                    gesturesEnabled = !cameraPanel.showing,
                    backHandlerEnabled = !cameraPanel.showing,
                    onDismissRequest = { if (!cameraPanel.showing) latestOnDismiss() },
                    tonalElevation = 0.dp,
                    content = content,
                )
            }
        }

        if (cameraPanel.showing) {
            BackHandler { onCloseCamera(false) }
            // 相机在附件面板之上，父容器覆盖整个窗口，不受平板永久侧栏影响。
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                ) { onCloseCamera(true) })
                Box(Modifier.align(Alignment.BottomCenter)
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth).fillMaxWidth(),
                    content = camera,
                )
            }
        }
    }
}

@Composable
private fun FilesPickerSheetLayer(
    cameraPanel: CameraPanelState,
    content: @Composable BoxScope.() -> Unit,
) {
    // BottomSheet 按内容高度测量，再在内部向下放置；alpha < 1 时离屏缓冲会裁掉测量边界外的内容。
    // 淡出层必须独立覆盖窗口，不能下移到 BottomSheet.modifier，也不能用逐指令透明度代替整体合成。
    Box(
        modifier = Modifier.fillMaxSize().graphicsLayer {
            alpha = if (cameraPanel.showing) cameraAttachmentsAlpha(cameraPanel.progress) else 1f
        },
        content = content,
    )
}
