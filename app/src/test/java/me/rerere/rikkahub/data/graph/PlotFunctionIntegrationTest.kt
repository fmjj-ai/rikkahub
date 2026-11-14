package me.rerere.rikkahub.data.graph

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.buildPlotFunctionTool
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.graph.clipGraphLine
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class PlotFunctionIntegrationTest {
    private val spec = GraphSpec(curves = listOf(GraphCurve("f", "asin(x)")))
    private fun tool(id: String = "call-1", output: List<UIMessagePart> = emptyList()) = UIMessagePart.Tool(
        toolCallId = id, toolName = PLOT_FUNCTION_TOOL_NAME,
        input = GraphProtocol.json.encodeToString(GraphRequest(listOf(spec))), output = output,
    )

    @Test fun threeIndependentGraphsArePersistedAsThreeDefinitions() = runBlocking {
        val plotTool = buildPlotFunctionTool()
        val request = GraphRequest(List(3) { spec.copy(title = "图 $it") })
        val result = plotTool.execute(GraphProtocol.json.parseToJsonElement(GraphProtocol.json.encodeToString(request)))
        val definition = GraphProtocol.decodeResult((result.single() as UIMessagePart.Text).text)
        assertTrue(definition.ok)
        assertEquals(3, definition.graphs.size)
        val message = UIMessage.assistant("").copy(parts = listOf(tool(output = result)))
        val historical = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(message))
        val historicalTool = historical.parts.single() as UIMessagePart.Tool
        assertEquals(message, historical)
        assertEquals(definition, GraphProtocol.decodeResult((historicalTool.output.single() as UIMessagePart.Text).text))
    }

    @Test fun malformedToolInputReturnsRecoverableError() = runBlocking {
        val result = buildPlotFunctionTool().execute(buildJsonObject { put("graphs", "not an array") })
        val error = GraphProtocol.decodeResult((result.single() as UIMessagePart.Text).text)
        assertFalse(error.ok)
        assertEquals("INVALID_SPEC", error.error?.code)
    }

    @Test fun escapedLabelsCannotCreateTruncatedHistoricalOutput() = runBlocking {
        val request = GraphRequest(List(4) { GraphSpec(title = "\u0001".repeat(120), curves = List(6) {
            GraphCurve("$it" + "\u0001".repeat(39), "1+".repeat(127) + "x", "\u0001".repeat(128))
        }) })
        val result = buildPlotFunctionTool().execute(
            GraphProtocol.json.parseToJsonElement(GraphProtocol.json.encodeToString(request))
        )
        val encoded = (result.single() as UIMessagePart.Text).text
        assertTrue(encoded.length <= GraphProtocol.MAX_JSON_LENGTH)
        val decoded = GraphProtocol.decodeResult(encoded)
        assertFalse(decoded.ok)
        assertEquals("RESOURCE_LIMIT", decoded.error?.code)
    }

    @Test fun toolSchemaIsStaticAndStrict() {
        val tool = buildPlotFunctionTool()
        val first = tool.parameters() as me.rerere.ai.core.InputSchema.Obj
        assertEquals(first, tool.parameters())
        assertEquals(false, first.additionalProperties)
        assertEquals(listOf("graphs"), first.required)
    }

    @Test fun fiftyCurvesAndSpecialPointsSurviveToolExecution() = runBlocking {
        val graph = GraphSpec(curves = List(50) {
            GraphCurve("f$it", "x^2+$it", points = listOf(GraphMarker(0.0, "极小值")))
        })
        val result = buildPlotFunctionTool().execute(GraphProtocol.json.parseToJsonElement(
            GraphProtocol.json.encodeToString(GraphRequest(listOf(graph)))
        ))
        val decoded = GraphProtocol.decodeResult((result.single() as UIMessagePart.Text).text)
        assertTrue(decoded.ok)
        assertEquals(graph, decoded.graphs.single())
        val tooMany = graph.copy(curves = graph.curves + GraphCurve("extra", "x"))
        val rejected = buildPlotFunctionTool().execute(GraphProtocol.json.parseToJsonElement(
            GraphProtocol.json.encodeToString(GraphRequest(listOf(tooMany)))
        ))
        assertEquals("RESOURCE_LIMIT", GraphProtocol.decodeResult((rejected.single() as UIMessagePart.Text).text).error?.code)
    }

    @Test fun graphBlockKeepsOrderAndDoesNotDuplicate() {
        val graphTool = tool()
        val ordinaryTool = UIMessagePart.Tool("ordinary", "get_time_info", "{}")
        val parts = listOf(UIMessagePart.Text("之前"), graphTool, ordinaryTool, UIMessagePart.Text("之后"))
        val blocks = parts.groupMessageParts()
        assertTrue(blocks[0] is MessagePartBlock.ContentBlock)
        assertTrue(blocks[1] is MessagePartBlock.ThinkingBlock)
        assertEquals(graphTool, (blocks[2] as MessagePartBlock.GraphDisplayBlock).tool)
        assertTrue(blocks[3] is MessagePartBlock.ThinkingBlock)
        assertEquals("之后", ((blocks[4] as MessagePartBlock.ContentBlock).part as UIMessagePart.Text).text)
        assertEquals(1, (parts + graphTool).groupMessageParts().filterIsInstance<MessagePartBlock.GraphDisplayBlock>().size)
        val streamed = parts.map { if (it == graphTool) graphTool.copy(output = listOf(UIMessagePart.Text("{}"))) else it }
        assertEquals(1, streamed.groupMessageParts().filterIsInstance<MessagePartBlock.GraphDisplayBlock>().size)
    }

    @Test fun toolSettingRoundTripPreservesExistingChoices() {
        val enabled = Assistant(localTools = listOf(LocalToolOption.FunctionGraph, LocalToolOption.TimeInfo))
        assertEquals(enabled.localTools, JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(enabled)).localTools)
        val existing = "{\"localTools\":[{\"type\":\"time_info\"}]}"
        val decoded = JsonInstant.decodeFromString<Assistant>(existing)
        assertEquals(listOf(LocalToolOption.TimeInfo), decoded.localTools)
        assertTrue(Assistant().localTools.contains(LocalToolOption.FunctionGraph))
    }

    @Test fun clippingHandlesSteepOffscreenAndOverflow() {
        val view = GraphViewport()
        val clipped = clipGraphLine(GraphPoint(-1.0, -1e6), GraphPoint(1.0, 1e6), view)!!
        assertEquals(-5.0, clipped.first.y, 1e-6)
        assertEquals(5.0, clipped.second.y, 1e-6)
        assertNull(clipGraphLine(GraphPoint(-1.0, 6.0), GraphPoint(1.0, 7.0), view))
        assertNull(clipGraphLine(GraphPoint(-1.0, -Double.MAX_VALUE), GraphPoint(1.0, Double.MAX_VALUE), view))
    }
}
