package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatPageOverlayHostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tabletCameraIsCenteredInTheWholeWindowInsteadOfTheChatPane() {
        verifyCentering(1200.dp, 800.dp, drawer = true)
    }

    @Test
    fun keyboardChangesTheAvailableHeightButNotTheTabletCameraCenter() {
        verifyCentering(1200.dp, 800.dp, drawer = true, keyboardHeight = 240.dp)
    }

    @Test
    fun phoneLayoutRemainsCentered() {
        verifyCentering(360.dp, 800.dp, drawer = false)
    }

    @Test
    fun narrowTabletSplitScreenRemainsCentered() {
        verifyCentering(540.dp, 700.dp, drawer = false, keyboardHeight = 240.dp)
    }

    private fun verifyCentering(width: Dp, height: Dp, drawer: Boolean, keyboardHeight: Dp = 0.dp) {
        var window = Rect.Zero
        var pane = Rect.Zero
        var available = Rect.Zero
        var camera = Rect.Zero
        compose.setContent {
            MaterialTheme {
                ChatPageOverlayHost(
                    modifier = Modifier.requiredSize(width, height).onGloballyPositioned { window = it.fullBounds() },
                    content = {
                        if (drawer) {
                            PermanentNavigationDrawer(
                                drawerContent = {
                                    PermanentDrawerSheet(Modifier.width(320.dp)) { }
                                },
                            ) {
                                Box(Modifier.fillMaxSize().onGloballyPositioned { pane = it.fullBounds() })
                            }
                        } else Box(Modifier.fillMaxSize())
                    },
                    overlay = {
                        Box(Modifier.fillMaxSize().padding(bottom = keyboardHeight)
                            .onGloballyPositioned { available = it.fullBounds() }
                        ) {
                            Box(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                                .height(200.dp).onGloballyPositioned { camera = it.fullBounds() })
                        }
                    },
                )
            }
        }
        compose.runOnIdle {
            assertTrue(window.width > 0f && camera.width > 0f)
            assertEquals(window.center.x, camera.center.x, 0.5f)
            assertEquals(available.bottom, camera.bottom, 0.5f)
            assertTrue(camera.left >= window.left && camera.right <= window.right)
            if (drawer) assertTrue(pane.center.x > camera.center.x + 1f)
            if (keyboardHeight > 0.dp) assertTrue(camera.bottom < window.bottom)
        }
    }

    private fun LayoutCoordinates.fullBounds() = Rect(positionInRoot(), size.toSize())
}
