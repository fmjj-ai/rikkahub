package me.rerere.rikkahub.data.graph

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class FunctionGraphTest {
    private fun value(expression: String, x: Double = 0.0) = ExpressionCompiler.compile(expression).evaluate(x)
    private fun graph(expression: String = "x") = GraphSpec(curves = listOf(GraphCurve("f", expression)))

    @Test fun inverseTrigonometryAndLogarithms() {
        assertEquals(PI / 2, value("arcsin(x)", 1.0), 1e-12)
        assertEquals(PI, value("arccos(x)", -1.0), 1e-12)
        assertEquals(PI / 4, value("arctan(x)", 1.0), 1e-12)
        assertTrue(value("asin(x)", 1.01).isNaN())
        assertEquals(1.0, value("ln(e)"), 1e-12)
        assertEquals(1.0, value("log(e)"), 1e-12)
        assertEquals(2.0, value("log10(100)"), 1e-12)
        assertEquals(0.0, value("sin(π)"), 1e-12)
    }

    @Test fun operatorPrecedenceAndScientificNotation() {
        assertEquals(-4.0, value("-2^2"), 0.0)
        assertEquals(4.0, value("(-2)^2"), 0.0)
        assertEquals(0.25, value("2^-2"), 0.0)
        assertEquals(512.0, value("2^3^2"), 0.0)
        assertEquals(0.001, value("1e-3"), 0.0)
        assertEquals(5.0, value("sqrt(abs(-9))+exp(0)*2"), 0.0)
    }

    @Test fun tokenNormalizationDoesNotRewriteUnknownNames() {
        assertEquals("asin(x) + pi", ExpressionCompiler.normalize("arcsin(x) + π"))
        listOf("myarcsin(x)", "sinx", "y", "xx", "x;exit()", "sqrt(-1);", "1..2", "sin x",
            "2x", "2(x)", "x+", "()", "sin()", "x**2", "(x", "x)", "1e999").forEach {
            assertThrows("应拒绝 $it", GraphValidationException::class.java) { ExpressionCompiler.compile(it) }
        }
    }

    @Test fun limitsVersionsAndFiniteRanges() {
        val invalid = listOf(
            listOf(graph().copy(version = 2)), emptyList(), List(5) { graph() },
            listOf(graph().copy(curves = List(51) { GraphCurve("$it", "x") })),
            listOf(graph().copy(curves = listOf(GraphCurve("f", "x"), GraphCurve("f", "x+1")))),
            listOf(graph().copy(viewport = GraphViewport(xMax = Double.POSITIVE_INFINITY))),
            listOf(graph().copy(viewport = GraphViewport(xMin = 5.0, xMax = -5.0))),
            listOf(graph().copy(curves = listOf(GraphCurve("f", "x", domain = GraphDomain(1.0, 1.0))))),
            listOf(graph("(".repeat(25) + "x" + ")".repeat(25))),
            listOf(graph("x".repeat(257))),
        )
        invalid.forEach { specs -> assertThrows(GraphValidationException::class.java) { GraphProtocol.validate(specs) } }
    }

    @Test fun graphDefinitionRoundTripHasNoSamples() {
        val original = GraphResult(true, GraphProtocol.validate(listOf(graph("arcsin(x)"))))
        val encoded = GraphProtocol.json.encodeToString(original)
        assertEquals(original, GraphProtocol.decodeResult(encoded))
        assertFalse(encoded.contains("segments"))
        assertThrows(Exception::class.java) { GraphProtocol.decodeResult("{\"ok\":true,\"graphs\":[],\"script\":\"x\"}") }
    }

    @Test fun maximumDefinitionFitsExistingToolOutputLimit() {
        val specs = List(4) { GraphSpec(title = "\"".repeat(120), curves = List(6) {
            GraphCurve("f$it", "1+".repeat(127) + "x", "\\".repeat(128))
        }) }
        val text = GraphProtocol.json.encodeToString(GraphResult(true, GraphProtocol.validate(specs)))
        assertTrue("最大工具结果应小于历史截断阈值：${text.length}", text.length < GraphProtocol.MAX_JSON_LENGTH)
    }

    private fun sample(expression: String, viewport: GraphViewport = GraphViewport(), domain: GraphDomain? = null) =
        runBlocking {
            FunctionSampler.sample(GraphCurve("f", expression, domain = domain), ExpressionCompiler.compile(expression), viewport)
        }

    @Test fun reciprocalAndTangentNeverBridgePoles() {
        val reciprocal = sample("1/x", GraphViewport(-4.7, 5.3, -5.0, 5.0))
        assertTrue(reciprocal.segments.size >= 2)
        reciprocal.segments.forEach { segment ->
            assertFalse(segment.first().x < 0 && segment.last().x > 0)
        }
        val tangent = sample("tan(x)", GraphViewport(-4.0, 4.0, -5.0, 5.0))
        assertTrue(tangent.segments.size >= 3)
        tangent.segments.forEach { segment ->
            listOf(-PI / 2, PI / 2).forEach { pole ->
                assertFalse(segment.first().x < pole && segment.last().x > pole)
            }
        }
        assertTrue(tangent.evaluations <= FunctionSampler.MAX_EVALUATIONS)
    }

    @Test fun inverseTrigAndSquareRootRespectRealDomain() {
        listOf("asin(x)", "acos(x)", "sqrt(1-x^2)").forEach { expression ->
            val points = sample(expression).segments.flatten()
            assertTrue(points.isNotEmpty())
            assertTrue(points.all { it.x in -1.0..1.0 && it.y.isFinite() })
        }
        val endpoints = sample("asin(x)", domain = GraphDomain(-1.0, 1.0)).segments.flatten()
        assertTrue(endpoints.any { it.x == -1.0 && abs(it.y + PI / 2) < 1e-12 })
        assertTrue(endpoints.any { it.x == 1.0 && abs(it.y - PI / 2) < 1e-12 })
    }

    @Test fun openDomainAndNaturalLog() {
        val open = sample("x", domain = GraphDomain(-1.0, 1.0, false, false)).segments.flatten()
        assertTrue(open.all { it.x > -1.0 && it.x < 1.0 })
        assertTrue(sample("ln(x)").segments.flatten().all { it.x > 0 })
        assertTrue(sample("sqrt(-1)").segments.isEmpty())
    }

    @Test fun constantSteepAndAsymptoticCurves() {
        assertEquals(1, sample("2").segments.size)
        assertTrue(sample("100000*x").segments.flatten().isNotEmpty())
        val arctangent = sample("atan(x)", GraphViewport(-100.0, 100.0, -2.0, 2.0)).segments.flatten()
        assertEquals(-100.0, arctangent.first().x, 0.0)
        assertEquals(100.0, arctangent.last().x, 0.0)
        assertTrue(arctangent.all { abs(it.y) < PI / 2 })
    }

    @Test fun viewportZoomPreservesFocusAndBounds() {
        val initial = GraphViewport()
        val zoom = initial.transform(0.25, 0.75, 0.0, 0.0, 2.0)
        assertEquals(initial.xMin + initial.width * 0.25, zoom.xMin + zoom.width * 0.25, 1e-12)
        assertEquals(initial.yMax - initial.height * 0.75, zoom.yMax - zoom.height * 0.75, 1e-12)
        repeat(100) { initial.transform(0.5, 0.5, 100.0, -100.0, 1e20).validate() }
        assertEquals(initial, initial.transform(0.5, 0.5, 0.0, 0.0, Double.NaN))
        assertTrue(graphTicks(-5.0, 5.0).all { it in -5.0..5.0 })
    }

    @Test fun cancelledSamplingCannotPublishResult() = runBlocking {
        var published = false
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            try {
                val expression = ExpressionCompiler.compile("tan(x)")
                started.complete(Unit)
                repeat(1000) {
                    FunctionSampler.sample(GraphCurve("f", "tan(x)"), expression, GraphViewport())
                }
                published = true
            } catch (_: CancellationException) { }
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(published)
    }
}
