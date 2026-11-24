package me.rerere.rikkahub.ui.components.visualization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.visualization.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VizFloatingControlPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun panelButtonDispatchesOnceAndDragResizeCollapseKeepState() {
        val state = VizPanelState().apply { width = 260f; height = 240f }
        val presentation = VizPresentationState()
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 520.dp)) {
                    VizFloatingControlPanel(state, presentation, "test", listOf(VizControlGroup.Actions)) {
                        Button(onClick = { clicks++ }) { Text("下一步") }
                    }
                }
            }
        }
        compose.onNodeWithText("下一步").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithContentDescription("拖动控制窗").performTouchInput {
            swipe(center, center + Offset(-40f, -80f))
        }
        compose.runOnIdle { assertTrue(state.xFraction < 1f); assertTrue(state.yFraction < 1f) }
        compose.onNodeWithContentDescription("调整控制窗大小").performTouchInput {
            swipe(center, center + Offset(-30f, -30f))
        }
        var preferredWidth = 0f
        compose.runOnIdle { preferredWidth = state.width!!; assertTrue(preferredWidth < 260f) }
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithText("下一步").assertDoesNotExist()
        compose.onNodeWithText("展开").performClick()
        compose.onNodeWithText("下一步").assertExists()
        compose.runOnIdle { assertEquals(preferredWidth, state.width); assertEquals(1, clicks) }
    }

    @Test fun numberDraftSurvivesGroupSwitchAndCollapseWithoutSubmitting() {
        val queue = VizQueue()
        val node = VizNode("numberInput", buildJsonObject {
            put("type", "numberInput"); put("id", "number"); put("bind", "n"); put("label", "数值")
            put("min", 0); put("max", 99); put("step", 1)
        })
        val session = VizSession(VizSpec("draft", "草稿", buildJsonObject { put("n", 1) },
            emptyMap(), listOf(node.values)), queue)
        val state = VizPanelState().apply { group = VizControlGroup.Parameters; width = 300f; height = 330f }
        val presentation = VizPresentationState()
        try {
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 520.dp)) {
                        VizFloatingControlPanel(state, presentation, "draft",
                            listOf(VizControlGroup.Parameters, VizControlGroup.Information)) {
                            if (it == VizControlGroup.Parameters) VizWidget(session, node, presentation, "draft")
                            else Text("信息")
                        }
                    }
                }
            }
            compose.onNode(hasSetTextAction()).performTextReplacement("7")
            compose.onAllNodesWithText("信息").onFirst().performClick()
            runBlocking { queue.submit {} }
            compose.runOnIdle {
                assertEquals(1, session.frame.state.value("n").int())
                assertEquals("7", presentation.numberDraft("draft", "number")?.text)
            }
            compose.onNodeWithText("参数").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("7")
            compose.onNode(hasSetTextAction()).performClick()
            compose.onNodeWithText("收起").performClick()
            runBlocking { queue.submit {} }
            compose.runOnIdle { assertEquals(1, session.frame.state.value("n").int()) }
            compose.onNodeWithText("展开").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("7")
            compose.onNode(hasSetTextAction()).performImeAction()
            runBlocking { queue.submit {} }
            compose.runOnIdle { assertEquals(7, session.frame.state.value("n").int()) }
        } finally { session.close(); queue.close() }
    }
}
