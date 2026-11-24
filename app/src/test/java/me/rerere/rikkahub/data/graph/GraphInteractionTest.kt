package me.rerere.rikkahub.data.graph

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class GraphInteractionTest {
    private val view = GraphViewport(-2.0, 2.0, -2.0, 2.0)

    @Test fun markersRoundTripAndInvalidPointsAreRejected() {
        val curve = GraphCurve("f", "asin(x)", points = listOf(GraphMarker(1.0, "端点")))
        val spec = GraphSpec(curves = listOf(curve))
        val result = GraphResult(true, GraphProtocol.validate(listOf(spec)))
        assertEquals(result, GraphProtocol.decodeResult(GraphProtocol.json.encodeToString(result)))
        assertEquals(PI / 2, resolveGraphMarkers(spec).single().point.y, 1e-12)
        listOf(curve.copy(points = listOf(GraphMarker(1.01))),
            curve.copy(points = listOf(GraphMarker(Double.NaN))),
            curve.copy(domain = GraphDomain(-1.0, 1.0, includeMax = false)),
            curve.copy(points = List(101) { GraphMarker(0.0) })).forEach { invalid ->
            assertThrows(GraphValidationException::class.java) { GraphProtocol.validate(listOf(spec.copy(curves = listOf(invalid)))) }
        }
        // 旧协议记录省略 points 时保持可读。
        assertTrue(GraphProtocol.decodeResult("""{"ok":true,"graphs":[{"curves":[{"id":"f","expression":"x"}]}]}""")
            .graphs.single().curves.single().points.isEmpty())
    }

    @Test fun curveLimitSupportsFiftyAndBoundedSamplingStillReachesRightEdge() = runBlocking {
        val curves = List(50) { GraphCurve("f$it", "sin(x)+$it") }
        assertEquals(50, GraphProtocol.validate(listOf(GraphSpec(curves = curves))).single().curves.size)
        val budget = FunctionSampler.MAX_GRAPH_EVALUATIONS / curves.size
        val samples = curves.map {
            FunctionSampler.sample(it, ExpressionCompiler.compile(it.expression), view, maxEvaluations = budget)
        }
        assertTrue(samples.sumOf { it.evaluations } <= FunctionSampler.MAX_GRAPH_EVALUATIONS)
        samples.forEach {
            assertEquals(view.xMin, it.segments.first().first().x, 0.0)
            assertEquals(view.xMax, it.segments.last().last().x, 0.0)
        }
    }

    @Test fun markerTakesPriorityAndCurveReadoutUsesActualFunction() = runBlocking {
        val curve = GraphCurve("f", "x^2", points = listOf(GraphMarker(0.0, "最低点")))
        val spec = GraphSpec(curves = listOf(curve))
        val expression = ExpressionCompiler.compile(curve.expression)
        val samples = listOf(FunctionSampler.sample(curve, expression.fork(), view))
        fun select(x: Double, y: Double) = selectGraphPoint(spec, view, samples, resolveGraphMarkers(spec),
            listOf(expression), x, y, 400.0, 400.0, 24.0)!!
        val marker = select(210.0, 193.0)
        assertEquals("最低点", marker.markerLabel)
        assertEquals(GraphPoint(0.0, 0.0), marker.point)
        val hit = select(300.0, 105.0)
        assertEquals(0, hit.curveIndex)
        assertEquals(hit.point.x * hit.point.x, hit.point.y, 1e-12)
        val empty = select(25.0, 350.0)
        assertNull(empty.curveIndex)
        assertEquals(-1.75, empty.point.x, 1e-12)
    }

    @Test fun polesAndOpenEndpointsCannotBeSelectedAsCurvePoints() = runBlocking {
        val curve = GraphCurve("f", "1/x")
        val spec = GraphSpec(curves = listOf(curve))
        val expr = ExpressionCompiler.compile(curve.expression)
        val samples = listOf(FunctionSampler.sample(curve, expr.fork(), view))
        val hit = selectGraphPoint(spec, view, samples, emptyList(), listOf(expr), 200.0, 200.0,
            400.0, 400.0, 24.0)!!
        assertNull(hit.curveIndex)
    }

    @Test fun mathematicalTypesettingPreservesPrecedenceAndLegacyLabels() {
        assertEquals("e^{-x^{2}}", graphExpressionLatex("e^(-x^2)"))
        assertEquals("\\frac{1}{\\sqrt{2}}", graphExpressionLatex("1/sqrt(2)"))
        assertEquals("\\arcsin\\left(x\\right)", graphExpressionLatex("asin(x)"))
        assertEquals("\\left(-x\\right)^{2}", graphExpressionLatex("(-x)^2"))
        assertEquals("x-\\left(x+1\\right)", graphExpressionLatex("x-(x+1)"))
        assertEquals("x^{x^{2}}", graphExpressionLatex("x^x^2"))
        assertEquals("\\left(e^{x}\\right)^{2}", graphExpressionLatex("exp(x)^2"))
        assertEquals("\\left(1\\times 10^{-3}\\right)^{2}", graphExpressionLatex("1e-3^2"))
        assertNull(graphExpressionLatex("bad(x)"))
        val parts = graphTextParts("拐点 (1/√2, e^(-1/2))")
        assertTrue(parts.any { it.math && "\\frac{1}{\\sqrt{2}}" in it.text })
        assertTrue(graphTextParts("y = e^(-x²)：最大值").any { it.math && it.text == "y=e^{-x^{2}}" })
        assertTrue(graphTextParts("极值 ${'$'}\\frac{1}{2}${'$'}").any { it.math && "\\frac" in it.text })
    }

    @Test fun ticksChangeSpacingAndLabelsRemainDistinctAtExtremeZoom() {
        assertTrue(graphTicks(-5.0, 5.0, 3).size < graphTicks(-5.0, 5.0, 10).size)
        listOf(-1e-6 to 1e-6, 999999999999.9 to 1e12, -5e8 to 5e8).forEach { (min, max) ->
            val ticks = graphTicks(min, max)
            val step = ticks[1] - ticks[0]
            val labels = ticks.map { formatGraphCoordinate(it, step) }
            assertEquals(labels.size, labels.distinct().size)
        }
        assertEquals("10", formatGraphCoordinate(10.0, 2.0))
        assertEquals("1.000000e-06", formatGraphCoordinate(1e-6))
    }
}
