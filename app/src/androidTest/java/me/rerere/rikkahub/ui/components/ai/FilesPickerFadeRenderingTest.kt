package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** 使用真实 BottomSheet 检查淡出像素，而不只检查节点存在、滚动状态或遮罩。 */
class FilesPickerFadeRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tallSheetKeepsItsFooterAndBottomBackgroundThroughoutBothDirections() {
        verifyFade(contentHeightFraction = 0.62f)
    }

    @Test
    fun shortSheetDoesNotDisappearOnTheFirstTranslucentFrame() {
        verifyFade(contentHeightFraction = 0.28f)
    }

    @Test
    fun resizingTheAvailableWindowKeepsTheWholeSheetInsideTheFadeLayer() {
        verifyFade(contentHeightFraction = 0.62f, resizeWhileHidden = true)
    }

    private fun verifyFade(contentHeightFraction: Float, resizeWhileHidden: Boolean = false) {
        val panel = CameraPanelState()
        var rootBounds = Rect.Zero
        var viewportBounds = Rect.Zero
        var contentBounds = Rect.Zero
        var footerBounds = Rect.Zero
        var windowHeight = 0.dp
        var bottomInset by mutableStateOf(0.dp)
        var mounts = 0
        var disposals = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                BoxWithConstraints(
                    Modifier.fillMaxSize().background(Color.Blue).testTag("fade-root")
                        .onGloballyPositioned { rootBounds = it.fullBounds() },
                ) {
                    windowHeight = maxHeight
                    Box(Modifier.fillMaxSize().padding(bottom = bottomInset)) {
                        BoxWithConstraints(
                            Modifier.fillMaxSize().onGloballyPositioned { viewportBounds = it.fullBounds() },
                        ) {
                            val sheetHeight = maxHeight * contentHeightFraction
                            FilesPickerOverlay(
                                cameraPanel = panel,
                                onDismissRequest = { error("相机交接不应关闭附件") },
                                onCloseCamera = { panel.requestClose(it) },
                                // 不绑定摄像头，单独证明面板不能因为开始合成就被裁断。
                                camera = {},
                            ) {
                                DisposableEffect(Unit) {
                                    mounts++
                                    onDispose { disposals++ }
                                }
                                Box(
                                    Modifier.fillMaxWidth().height(sheetHeight).background(Color.Red)
                                        .onGloballyPositioned { contentBounds = it.fullBounds() },
                                ) {
                                    Box(
                                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                            .height(sheetHeight * 0.25f).background(Color.Green)
                                            .onGloballyPositioned { footerBounds = it.fullBounds() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle {
            assertEquals(1, mounts)
            panel.open()
            panel.updateProgress(1f)
            if (resizeWhileHidden) bottomInset = windowHeight * 0.2f
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()

        // 所有点都来自实际布局；不能用节点截图，否则裁剪外的底部像素可能根本没有被检查。
        val points = listOf(
            Offset(contentBounds.center.x, contentBounds.top + contentBounds.height * 0.1f),
            footerBounds.center,
            Offset(viewportBounds.center.x, viewportBounds.bottom - 2f),
        ).map { it - rootBounds.topLeft }
        val background = sampleRoot(points)
        fun setProgress(value: Float) {
            compose.runOnIdle { panel.updateProgress(value) }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        setProgress(0f)
        val foreground = sampleRoot(points)
        assertColorNear("面板上部基准", Color.Red, foreground[0])
        assertColorNear("底部按钮区基准", Color.Green, foreground[1])
        foreground.zip(background).forEachIndexed { index, (front, back) ->
            assertTrue("探针 $index 必须确实位于不透明面板内", colorDistance(front, back) > 0.1f)
        }
        val stableContentBounds = contentBounds
        val stableFooterBounds = footerBounds
        fun assertFrame(progress: Float) {
            setProgress(progress)
            val alpha = cameraAttachmentsAlpha(progress)
            val actual = sampleRoot(points)
            points.indices.forEach { index ->
                val front = foreground[index]
                val back = background[index]
                val expected = Color(
                    red = front.red * alpha + back.red * (1f - alpha),
                    green = front.green * alpha + back.green * (1f - alpha),
                    blue = front.blue * alpha + back.blue * (1f - alpha),
                )
                assertColorNear("进度 $progress，探针 $index", expected, actual[index])
            }
            compose.runOnIdle {
                assertEquals(stableContentBounds, contentBounds)
                assertEquals(stableFooterBounds, footerBounds)
                assertEquals(1, mounts)
                assertEquals(0, disposals)
            }
        }

        // 0.001 专门覆盖 alpha 刚低于 1 的第一帧，旧实现在此帧突然裁掉整个底部。
        val progressFrames = listOf(0f, 0.001f, 0.025f, 0.125f, 0.249f, 0.25f, 1f)
        progressFrames.forEach(::assertFrame)
        compose.runOnIdle { panel.requestClose() }
        progressFrames.asReversed().forEach(::assertFrame)
        compose.runOnIdle {
            assertEquals(CameraCloseAction.RETURN_TO_ATTACHMENTS, panel.finishClose())
        }
        compose.mainClock.advanceTimeByFrame()
        sampleRoot(points).forEachIndexed { index, color ->
            assertColorNear("返回后探针 $index", foreground[index], color)
        }
    }

    private fun sampleRoot(points: List<Offset>): List<Color> {
        val pixels = compose.onNodeWithTag("fade-root").captureToImage().toPixelMap()
        return points.map { point ->
            val x = point.x.toInt()
            val y = point.y.toInt()
            assertTrue("探针必须位于实际截图内：$point", x in 0 until pixels.width && y in 0 until pixels.height)
            pixels[x, y]
        }
    }

    private fun assertColorNear(message: String, expected: Color, actual: Color) {
        assertTrue("$message：预期 $expected，实际 $actual", colorDistance(expected, actual) < 0.025f)
    }

    private fun colorDistance(first: Color, second: Color): Float = maxOf(
        abs(first.red - second.red), abs(first.green - second.green), abs(first.blue - second.blue),
    )

    // 不用会受祖先裁剪影响的 boundsInRoot，避免错误实现让探针跟着被截短。
    private fun LayoutCoordinates.fullBounds() = Rect(positionInRoot(), size.toSize())
}
