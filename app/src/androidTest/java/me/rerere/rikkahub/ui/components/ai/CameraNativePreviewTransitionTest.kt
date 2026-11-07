package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 使用真正的 TextureView，不需要摄像头权限；在手机和平板上验证原生层不随转场漂移或遮挡。 */
class CameraNativePreviewTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nativeTextureRemainsFixedAndDoesNotMaskTheBackgroundInEitherDirection() {
        var visible by mutableStateOf(true)
        var closeReady by mutableStateOf(false)
        var progress = 0f
        var closed = 0
        var textureReady = false
        var streaming by mutableStateOf(false)
        var native: FrameLayout? = null
        var fixedBounds = Rect.Zero
        var rootBounds = Rect.Zero
        val frame = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }.asImageBitmap()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Green).testTag("root")
                    .onGloballyPositioned { rootBounds = it.boundsInWindow() }
                ) {
                    Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()) {
                        CameraOpeningTransition(
                            source = Rect(rootBounds.center.x - 20f, rootBounds.bottom - 70f,
                                rootBounds.center.x + 20f, rootBounds.bottom - 20f),
                            visible = visible,
                            readyToClose = closeReady,
                            onProgress = { progress = it },
                            onClosed = { closed++ },
                            stationaryPreview = { atRest ->
                                val show = useStationaryCameraPreview(streaming, atRest, visible, closeReady)
                                AndroidView(
                                    factory = { context ->
                                        FrameLayout(context).apply {
                                            native = this
                                            background = GradientDrawable().apply {
                                                setColor(android.graphics.Color.BLACK)
                                                cornerRadius = 32f
                                            }
                                            clipToOutline = true
                                            addView(TextureView(context).apply {
                                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                    override fun onSurfaceTextureAvailable(
                                                        t: SurfaceTexture, w: Int, h: Int,
                                                    ) {
                                                        val surface = Surface(t)
                                                        try {
                                                            val canvas = surface.lockCanvas(null)
                                                            canvas.drawColor(android.graphics.Color.MAGENTA)
                                                            surface.unlockCanvasAndPost(canvas)
                                                            textureReady = true
                                                            streaming = true
                                                        } finally {
                                                            surface.release()
                                                        }
                                                    }
                                                    override fun onSurfaceTextureSizeChanged(
                                                        t: SurfaceTexture, w: Int, h: Int,
                                                    ) {}
                                                    override fun onSurfaceTextureDestroyed(t: SurfaceTexture) = true
                                                    override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                                                }
                                            }, FrameLayout.LayoutParams(-1, -1))
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                        .stationaryCameraPreview(show, initializing = !streaming)
                                        .onGloballyPositioned { fixedBounds = it.boundsInWindow() },
                                )
                            },
                        ) { atRest ->
                            val show = useStationaryCameraPreview(streaming, atRest, visible, closeReady)
                            Box(Modifier.fillMaxWidth().height(240.dp)) {
                                if (!show) Image(
                                    frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(120)
        var nativeWidth = 0
        var nativeHeight = 0
        var nativePosition = IntArray(2)
        var expectedBounds = Rect.Zero
        compose.runOnIdle {
            assertTrue(progress > 0f && progress < 1f)
            nativeWidth = native!!.width
            nativeHeight = native!!.height
            native!!.getLocationInWindow(nativePosition)
            expectedBounds = fixedBounds
        }
        assertBackgroundUnmasked(rootBounds, expectedBounds)
        compose.mainClock.advanceTimeBy(450)
        compose.waitUntil(5000) { textureReady }
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            // 返回帧未准备好时，不得提前缩回或闪成空白。
            assertEquals(1f, progress, 0.001f)
            assertEquals(0, closed)
            closeReady = true
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            val actual = IntArray(2)
            native!!.getLocationInWindow(actual)
            assertEquals(nativePosition.toList(), actual.toList())
            assertEquals(nativeWidth, native!!.width)
            assertEquals(nativeHeight, native!!.height)
            assertEquals(expectedBounds, fixedBounds)
            assertTrue(native!!.clipToOutline)
            assertEquals(1f, native!!.scaleX, 0f)
            assertEquals(1f, native!!.scaleY, 0f)
            assertTrue(progress > 0f && progress < 1f)
        }
        assertBackgroundUnmasked(rootBounds, expectedBounds)
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(1, closed) }
    }

    private fun assertBackgroundUnmasked(root: Rect, native: Rect) {
        // 这个点在原生预览完整矩形内、缩放中的画面之外，之前的占位框测试未覆盖该区域。
        val pixels = compose.onNodeWithTag("root").captureToImage().toPixelMap()
        val x = (native.left - root.left + 20f).toInt()
        val y = (native.top - root.top + 20f).toInt()
        assertEquals(Color.Green, pixels[x, y])
    }
}
