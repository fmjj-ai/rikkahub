package me.rerere.rikkahub.ui.components.ai

import android.content.res.Configuration
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.hooks.isPhoneLayout
import me.rerere.rikkahub.R
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FilesPickerSectionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun phonePlacesMediaBelowTools() = verifyOrder(393, mediaBelowTools = true)

    @Test
    fun tabletKeepsCommittedMediaFirstOrder() = verifyOrder(800, mediaBelowTools = false)

    @Test
    fun tabletBoundaryKeepsCommittedMediaFirstOrder() = verifyOrder(600, mediaBelowTools = false)

    @Test
    fun phonePlacesRecentPhotosAboveButtonsAndPhotoBeforeCamera() = verifyMediaOrder(phone = true)

    @Test
    fun tabletKeepsButtonsAboveRecentPhotosAndCameraBeforePhoto() = verifyMediaOrder(phone = false)

    @Test
    fun phoneOmitsManagementEntriesWithoutRemovingTheWorkspace() {
        compose.setContent {
            Column {
                Text("工作区卡片")
                AttachmentManagementTools(phoneLayout = true) {
                    Text("MCP入口")
                    Text("扩展入口")
                }
            }
        }
        compose.onNodeWithText("工作区卡片").assertIsDisplayed()
        compose.onNodeWithText("MCP入口").assertDoesNotExist()
        compose.onNodeWithText("扩展入口").assertDoesNotExist()
    }

    @Test
    fun tabletRetainsBothManagementEntries() {
        compose.setContent {
            Column {
                AttachmentManagementTools(phoneLayout = false) {
                    Text("MCP入口")
                    Text("扩展入口")
                }
            }
        }
        compose.onNodeWithText("MCP入口").assertIsDisplayed()
        compose.onNodeWithText("扩展入口").assertIsDisplayed()
    }

    private fun verifyMediaOrder(phone: Boolean) {
        var recent = Rect.Zero
        var actions = Rect.Zero
        var cwd = Rect.Zero
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            Column {
                FilesPickerMediaSections(
                    phoneLayout = phone,
                    recentPhotos = {
                        Box(Modifier.height(60.dp).width(300.dp)
                            .onGloballyPositioned { recent = it.boundsInWindow() })
                    },
                    workspaceCwd = {
                        Box(Modifier.height(40.dp).width(300.dp)
                            .onGloballyPositioned { cwd = it.boundsInWindow() })
                    },
                    buttons = {
                        Row(Modifier.onGloballyPositioned { actions = it.boundsInWindow() }) {
                            PhotoCaptureActions(phoneLayout = phone, onPickImage = {}, onTakePic = {})
                        }
                    },
                )
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photo = compose.onNodeWithText(context.getString(R.string.photo)).fetchSemanticsNode().boundsInRoot
        val camera = compose.onNodeWithText(context.getString(R.string.take_picture)).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            if (phone) {
                assertEquals(8f * density, actions.top - recent.bottom, 0.5f)
                assertEquals(8f * density, cwd.top - actions.bottom, 0.5f)
                assertTrue(photo.left < camera.left)
            } else {
                assertTrue(actions.bottom <= recent.top)
                assertTrue(camera.left < photo.left)
                assertEquals(Rect.Zero, cwd)
            }
        }
    }

    private fun verifyOrder(smallestWidth: Int, mediaBelowTools: Boolean) {
        var media = Rect.Zero
        var tools = Rect.Zero
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                smallestScreenWidthDp = smallestWidth
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                FilesPickerSections(
                    phoneLayout = isPhoneLayout(),
                    modifier = Modifier.width(320.dp),
                    attachments = {
                        Box(Modifier.height(60.dp).width(300.dp).onGloballyPositioned { media = it.boundsInWindow() })
                    },
                    tools = {
                        Box(Modifier.height(80.dp).width(300.dp).onGloballyPositioned { tools = it.boundsInWindow() })
                    },
                )
            }
        }
        compose.runOnIdle {
            assertTrue(media.height > 0f && tools.height > 0f)
            if (mediaBelowTools) assertTrue(media.top > tools.bottom)
            else assertTrue(tools.top > media.bottom)
        }
    }
}
