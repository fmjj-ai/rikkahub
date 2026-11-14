package me.rerere.rikkahub.ui.components.graph

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.graph.GraphCurve
import me.rerere.rikkahub.data.graph.GraphProtocol
import me.rerere.rikkahub.data.graph.GraphResult
import me.rerere.rikkahub.data.graph.GraphSpec
import me.rerere.rikkahub.data.graph.GraphMarker
import me.rerere.rikkahub.data.graph.GraphSelection
import me.rerere.rikkahub.data.graph.GraphViewport
import me.rerere.rikkahub.data.graph.GraphPoint
import me.rerere.rikkahub.data.graph.graphExpressionLatex
import me.rerere.rikkahub.ui.components.richtext.getLatexDrawable
import me.rerere.rikkahub.data.graph.PLOT_FUNCTION_TOOL_NAME
import me.rerere.rikkahub.ui.components.message.tools.PlotFunctionToolContent
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

class FunctionGraphInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val spec = GraphSpec(title = "反正弦", curves = listOf(GraphCurve("f", "asin(x)")))

    @Test fun inlineCardOpensExpandedGraphAndControls() {
        compose.setContent { MaterialTheme { FunctionGraphCard(spec) } }
        compose.onNodeWithText("放大查看").performClick()
        compose.onNodeWithText("复位").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("拉长横轴").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("拉长纵轴").assertIsDisplayed().performClick()
        compose.onNodeWithText("放大").performClick()
        compose.onNodeWithText("缩小").performClick()
        compose.onNodeWithText("复位").performClick()
        compose.onNodeWithText("横纵比 1 : 1").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("放大查看").assertIsDisplayed()
    }

    @Test fun axisButtonsStretchOnEveryClickAndDoubleTapRestoresDefault() {
        compose.setContent { MaterialTheme { FunctionGraphExpanded(spec, onDismiss = {}) } }
        compose.onNodeWithText("横纵比 1 : 1").assertIsDisplayed()
        // 连续点击不是开关：每次都继续拉长同一轴。
        compose.onNodeWithContentDescription("拉长横轴").performClick()
        compose.onNodeWithText("横纵比 1.25 : 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("拉长横轴").performClick()
        compose.onNodeWithText("横纵比 1.56 : 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("反正弦，", substring = true).performTouchInput { doubleClick() }
        compose.onNodeWithText("横纵比 1 : 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("拉长纵轴").performClick()
        compose.onNodeWithText("横纵比 1 : 1.25").assertIsDisplayed()
        // 画布以外的空白处双击同样恢复默认。
        compose.onNodeWithText("拖动平移", substring = true).performTouchInput { doubleClick() }
        compose.onNodeWithText("横纵比 1 : 1").assertIsDisplayed()
    }

    @Test fun inlineSemicircleStaysRoundAcrossCardWidths() {
        var width by mutableStateOf(280.dp)
        val graph = GraphSpec(title = "等比例半圆", showLegend = false,
            curves = listOf(GraphCurve("f", "sqrt(1-x^2)")),
            viewport = GraphViewport(-1.3, 1.3, -1.3, 1.3))
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                FunctionGraphCard(graph, Modifier.width(width))
            }
        }
        fun assertRound() {
            val node = compose.onNodeWithContentDescription("等比例半圆，", substring = true)
            val primary = lightColorScheme().primary
            compose.waitUntil(10_000) {
                val pixels = node.captureToImage().toPixelMap()
                var minX = pixels.width; var maxX = -1
                var minY = pixels.height; var maxY = -1
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (abs(color.red - primary.red) < 0.03 && abs(color.green - primary.green) < 0.03 &&
                        abs(color.blue - primary.blue) < 0.03) {
                        minX = minOf(minX, x); maxX = maxOf(maxX, x)
                        minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    }
                }
                maxX > minX && maxY > minY && abs((maxX - minX) - 2 * (maxY - minY)) < 10
            }
        }
        assertRound()
        compose.runOnIdle { width = 180.dp }
        assertRound()
    }

    @Test fun historicalToolRendersThreeCardsAcrossThemeChange() {
        val result = GraphResult(true, List(3) { spec.copy(title = "图 $it") })
        val tool = UIMessagePart.Tool("graph-call", PLOT_FUNCTION_TOOL_NAME, "{}",
            output = listOf(UIMessagePart.Text(GraphProtocol.json.encodeToString(result))))
        var dark by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                PlotFunctionToolContent(tool, false)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("放大查看").fetchSemanticsNodes().size == 3 }
        compose.onAllNodesWithText("放大查看").assertCountEquals(3)
        compose.runOnIdle { dark = true }
        compose.onAllNodesWithText("放大查看").assertCountEquals(3)
    }

    @Test fun corruptedHistoryKeepsFollowingContent() {
        val tool = UIMessagePart.Tool("graph-call", PLOT_FUNCTION_TOOL_NAME, "{}",
            output = listOf(UIMessagePart.Text("{broken")))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.height(500.dp)) {
                    PlotFunctionToolContent(tool, false)
                    androidx.compose.material3.Text("后续正文")
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("此绘图记录无效或版本不受支持，可在工具详情查看原始数据").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("后续正文").assertIsDisplayed()
    }

    @Test fun markerIsPaintedAsDotAndTapSnapsToIt() {
        val graph = GraphSpec(curves = listOf(GraphCurve("f", "x^2", points = listOf(GraphMarker(0.0, "最低点")))),
            viewport = GraphViewport(-2.0, 2.0, -2.0, 2.0))
        var selection by mutableStateOf<GraphSelection?>(null)
        val primary = lightColorScheme().primary
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                FunctionGraphCanvas(graph, graph.viewport, Modifier.size(300.dp).testTag("graph"),
                    selection = selection, onSelection = { selection = it })
            }
        }
        val pixels = compose.onNodeWithTag("graph").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        fun close(a: Color, b: Color) = abs(a.red - b.red) < 0.03 && abs(a.green - b.green) < 0.03 &&
            abs(a.blue - b.blue) < 0.03
        assertTrue("特殊点中心应使用曲线颜色", close(primary, center))
        compose.onNodeWithTag("graph").performTouchInput { click(Offset(width / 2f + 5, height / 2f - 5)) }
        compose.runOnIdle {
            assertEquals("最低点", selection?.markerLabel)
            assertEquals(GraphPoint(0.0, 0.0), selection?.point)
        }
        compose.onNodeWithText("(0, 0)").assertIsDisplayed()
    }

    @Test fun generatedFormulaDrawablesSupportPowersFractionsAndInverseTrig() {
        listOf("e^(-x^2)", "1/sqrt(2)", "asin(x)", "exp(x)^2", "1e-3^2", "x-(x+1)").forEach {
            val latex = graphExpressionLatex(it)!!
            val drawable = getLatexDrawable(latex, 36f, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT)
            assertNotNull("公式必须生成可绘制对象：$latex", drawable)
            assertTrue(drawable!!.bounds.width() > 0 && drawable.bounds.height() > 0)
        }
    }
}
