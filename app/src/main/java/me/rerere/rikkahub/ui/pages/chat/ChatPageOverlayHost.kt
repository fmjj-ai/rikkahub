package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
internal fun ChatPageOverlayHost(
    modifier: Modifier = Modifier,
    modalOverlayVisible: Boolean = false,
    content: @Composable () -> Unit,
    overlay: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        // 同窗口模态浮层需要主动隔离底层无障碍节点，避免焦点穿过附件或相机。
        Box(if (modalOverlayVisible) Modifier.clearAndSetSemantics { } else Modifier) { content() }
        overlay()
    }
}
