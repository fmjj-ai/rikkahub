package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatScrollControllerTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private lateinit var controller: ChatScrollController
    private lateinit var intent: ChatScrollIntent
    private val generating = mutableStateOf(true)
    private val lastHeight = mutableIntStateOf(160)
    private val messages = mutableIntStateOf(12)

    private fun content() {
        compose.setContent {
            list = rememberLazyListState()
            val scope = rememberCoroutineScope()
            intent = remember { ChatScrollIntent() }
            controller = remember { ChatScrollController(list, scope, intent) }
            val keys = buildList {
                repeat(messages.intValue) { add("message:$it") }
                if (generating.value) add("loading")
                add(ChatScrollController.ScrollTailKey)
            }
            DisposableEffect(controller) { onDispose { controller.close() } }
            SideEffect { controller.onState(keys, messages.intValue, generating.value, true) }
            LaunchedEffect(list) {
                snapshotFlow {
                    Triple(list.layoutInfo.visibleItemsInfo.map { it.key to (it.offset to it.size) },
                        list.layoutInfo.totalItemsCount, list.canScrollForward)
                }.collect { controller.onLayoutChanged() }
            }
            LazyColumn(Modifier.fillMaxWidth().height(400.dp).testTag("messages")
                .nestedScroll(controller.nestedScrollConnection), state = list) {
                repeat(messages.intValue) { index ->
                    item(key = "message:$index") {
                        Box(Modifier.fillMaxWidth().height(
                            if (index == messages.intValue - 1) lastHeight.intValue.dp else 160.dp,
                        ))
                    }
                }
                if (generating.value) item(key = "loading") { Box(Modifier.height(30.dp)) }
                item(key = ChatScrollController.ScrollTailKey) { Box(Modifier.height(5.dp)) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { controller.onInitialBottom() }
        compose.waitForIdle()
    }

    @Test
    fun generationCompletionAndLaterHeightChangesDoNotFollowTail() {
        content()
        var anchor: ChatScrollController.Anchor? = null
        compose.runOnIdle {
            assertFalse(list.canScrollForward)
            assertTrue(intent.following)
            anchor = controller.currentAnchor()
            generating.value = false
            lastHeight.intValue = 550
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(list.canScrollForward)
            assertEquals(anchor, controller.currentAnchor())
            lastHeight.intValue = 850
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(anchor, controller.currentAnchor()) }
    }

    @Test
    fun userDragCancelsFollowingAndCompletionDoesNotMoveHistoryAnchor() {
        content()
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        var anchor: ChatScrollController.Anchor? = null
        compose.runOnIdle {
            assertFalse(intent.following)
            anchor = controller.currentAnchor()
            generating.value = false
            lastHeight.intValue = 550
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(anchor, controller.currentAnchor()) }
    }

    @Test
    fun viewerSuspendsScrollAndRestoresTailIntentOnClose() {
        content()
        var anchor: ChatScrollController.Anchor? = null
        compose.runOnIdle {
            controller.onViewer(true)
            anchor = controller.currentAnchor()
            lastHeight.intValue = 650
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor, controller.currentAnchor())
            controller.onViewer(false)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(intent.following)
            assertFalse(list.canScrollForward)
        }
    }

    @Test
    fun targetMessageWaitsUntilItActuallyAppears() {
        content()
        compose.runOnIdle { controller.onPreviewJump("message:12") }
        compose.waitForIdle()
        compose.runOnIdle { messages.intValue = 15 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(intent.following)
            assertEquals(12, list.firstVisibleItemIndex)
        }
    }

    @Test
    fun manualReturnToBottomResumesFollowing() {
        content()
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(intent.following) }
        repeat(12) {
            compose.onNodeWithTag("messages").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertFalse(list.canScrollForward)
            assertTrue(intent.following)
            lastHeight.intValue = 650
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(list.canScrollForward) }
    }

    @Test
    fun queuedFollowIsRevokedAtEndButExplicitBottomStillWorksOnce() {
        content()
        compose.runOnIdle {
            controller.onLayoutChanged()
            controller.onGeneration(false)
            generating.value = false
            lastHeight.intValue = 700
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(list.canScrollForward)
            controller.onJumpToBottom()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(list.canScrollForward)
            lastHeight.intValue = 1000
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(list.canScrollForward) }
    }

    @Test
    fun unfinishedSendTailIsCancelledButExplicitNodeSurvivesCompletion() {
        content()
        compose.runOnIdle {
            controller.onSend(minMessages = 20)
            generating.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { messages.intValue = 21 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(list.canScrollForward)
            controller.onPreviewJump("message:18")
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(18, list.firstVisibleItemIndex) }
    }

    @Test
    fun viewerCloseAfterCompletionDoesNotUseStaleFollowingIntent() {
        content()
        var anchor: ChatScrollController.Anchor? = null
        compose.runOnIdle {
            controller.onViewer(true)
            anchor = controller.currentAnchor()
            generating.value = false
            lastHeight.intValue = 700
        }
        compose.waitForIdle()
        compose.runOnIdle { controller.onViewer(false) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(anchor, controller.currentAnchor())
            assertTrue(list.canScrollForward)
        }
    }

    @Test
    fun layoutBecomingShorterDoesNotReauthorizeFollowing() {
        content()
        compose.runOnIdle {
            controller.onBrowse()
            lastHeight.intValue = 20
        }
        compose.waitForIdle()
        compose.runOnIdle {
            controller.onUserScrollFinished()
            assertFalse(intent.following)
            lastHeight.intValue = 800
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(list.canScrollForward) }
    }
}
