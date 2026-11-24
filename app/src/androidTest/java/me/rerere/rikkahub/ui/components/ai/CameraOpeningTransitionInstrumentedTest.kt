package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraOpeningTransitionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cameraStaysMountedDuringReturnAndCompletesOnlyAfterShrinking() {
        var visible by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var completions = 0
        var progress = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (mounted) {
                CameraOpeningTransition(
                    source = Rect(20f, 400f, 100f, 470f),
                    visible = visible,
                    onClosed = { completions++; mounted = false },
                    onProgress = { progress = it },
                ) {
                    Box(Modifier.size(200.dp, 300.dp).testTag("camera"))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(450)
        compose.runOnIdle {
            assertEquals(1f, progress, 0.001f)
            visible = false
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle {
            assertTrue(mounted)
            assertEquals(0, completions)
            assertTrue(progress > 0f && progress < 1f)
        }
        compose.mainClock.advanceTimeBy(350)
        compose.runOnIdle { assertEquals(1, completions) }
        compose.onNodeWithTag("camera").assertDoesNotExist()
    }
}
