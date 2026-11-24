package me.rerere.rikkahub.ui.components.ai

import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FilesPickerOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun phoneReturnKeepsOneWindowOneScrimAndTheSameAttachments() {
        verifyReturn(360.dp, 800.dp, checkScrim = true)
    }

    @Test
    fun tabletReturnUsesTheSameWindowAndDoesNotRecreateTheAttachments() {
        verifyReturn(1200.dp, 800.dp)
    }

    private fun verifyReturn(width: Dp, height: Dp, checkScrim: Boolean = false) {
        val panel = CameraPanelState()
        val scroll = ScrollState(0)
        var sheetMounts = 0
        var sheetDisposals = 0
        var cameraMounts = 0
        var cameraDisposals = 0
        var closed = 0
        var root: View? = null
        var sheetRoot: View? = null
        var cameraRoot: View? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                root = LocalView.current.rootView
                Box(Modifier.requiredSize(width, height).background(Color.White)) {
                    FilesPickerOverlay(
                        cameraPanel = panel,
                        onDismissRequest = { error("返回相机不应关闭附件") },
                        onCloseCamera = { panel.requestClose(it) },
                        camera = {
                            cameraRoot = LocalView.current.rootView
                            DisposableEffect(Unit) {
                                cameraMounts++
                                onDispose { cameraDisposals++ }
                            }
                            CameraOpeningTransition(
                                source = Rect(120f, 600f, 200f, 660f),
                                visible = panel.expanded,
                                onProgress = panel::updateProgress,
                                onClosed = { panel.finishClose(); closed++ },
                            ) {
                                Box(Modifier.size(240.dp, 200.dp).background(Color.Black).testTag("camera"))
                            }
                        },
                    ) {
                        sheetRoot = LocalView.current.rootView
                        DisposableEffect(Unit) {
                            sheetMounts++
                            onDispose { sheetDisposals++ }
                        }
                        Box(Modifier.fillMaxWidth().height(300.dp).verticalScroll(scroll).testTag("attachments")) {
                            Box(Modifier.fillMaxWidth().height(600.dp).background(Color.Red))
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle {
            assertEquals(1, sheetMounts)
            assertSame(root, sheetRoot)
            scroll.dispatchRawDelta(80f)
        }
        compose.mainClock.advanceTimeByFrame()
        val scrimBefore = if (checkScrim) compose.onRoot().captureToImage().toPixelMap()[4, 4] else null
        compose.runOnIdle { panel.open() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(450)
        compose.runOnIdle {
            assertSame(root, cameraRoot)
            assertEquals(1, sheetMounts)
            assertEquals(0, sheetDisposals)
            assertEquals(1f, panel.progress, 0.001f)
            panel.requestClose()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle {
            assertTrue(panel.showing)
            assertTrue(panel.progress > 0f && panel.progress < 1f)
            assertEquals(1, cameraMounts)
            assertEquals(0, cameraDisposals)
            assertEquals(1, sheetMounts)
            assertEquals(0, sheetDisposals)
            assertEquals(80, scroll.value)
        }
        if (checkScrim) assertEquals(scrimBefore, compose.onRoot().captureToImage().toPixelMap()[4, 4])
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(1, closed)
            assertEquals(1, cameraDisposals)
            assertEquals(1, sheetMounts)
            assertEquals(0, sheetDisposals)
            assertEquals(80, scroll.value)
        }
        compose.onNodeWithTag("camera").assertDoesNotExist()
        compose.onNodeWithTag("attachments").assertExists()
        if (checkScrim) assertEquals(scrimBefore, compose.onRoot().captureToImage().toPixelMap()[4, 4])
    }
}
