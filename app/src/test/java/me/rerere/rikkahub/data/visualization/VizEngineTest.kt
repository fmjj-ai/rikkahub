package me.rerere.rikkahub.data.visualization

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.visualization.VizSessionStore
import org.junit.Assert.*
import org.junit.Test

class VizEngineTest {
    private fun fixture(name: String): String = javaClass.getResource("/visualization/$name.json")!!.readText()
    private fun parse(body: String) = VizParser.parse(body)
    private fun expression(body: String): JsonElement = Json.parseToJsonElement(body)
    private fun minimal(state: String = "{}", actions: String = "{}", view: String = "[]") =
        """{"version":1,"id":"test","title":"测试","state":$state,"actions":$actions,"view":$view}"""

    @Test
    fun counterAndCpuFixturesStepAndRespectBoundaries() {
        val counter = parse(fixture("register-counter"))
        var frame = VizEngine.frame(counter, counter.initialState)
        repeat(3) { frame = VizEngine.dispatch(counter, frame, "next") }
        assertEquals(3.0, frame.state.number("n"), 0.0)
        val text = frame.nodes.first().shapes.filter { it.type == "text" }.map { it.values.string("text") }
        assertTrue(text.containsAll(listOf("3", "6")))
        val cpu = parse(fixture("cpu-single-bus"))
        var cpuFrame = VizEngine.frame(cpu, cpu.initialState)
        repeat(4) { cpuFrame = VizEngine.dispatch(cpu, cpuFrame, "next") }
        assertEquals(3.0, cpuFrame.state.number("step"), 0.0)
        assertTrue(cpuFrame.nodes.first().shapes.any { it.type == "text" && it.values.string("text") == "12" })
        repeat(4) { cpuFrame = VizEngine.dispatch(cpu, cpuFrame, "prev") }
        assertEquals(0.0, cpuFrame.state.number("step"), 0.0)
    }

    @Test
    fun parabolaSliderQuantizesAndCanvasYAxisPointsUp() {
        val spec = parse(fixture("parabola"))
        var frame = VizEngine.frame(spec, spec.initialState)
        val sliders = frame.nodes.filter { it.type == "slider" }
        frame = VizEngine.slider(spec, frame, sliders[0], -1.02)
        frame = VizEngine.slider(spec, frame, sliders[1], 2.01)
        assertEquals(-1.0, frame.state.number("a"), 1e-10)
        assertEquals(2.0, frame.state.number("b"), 1e-10)
        val curve = frame.nodes.first { it.type == "scene" }.shapes.first { it.type == "plot" }.paths.single()
        assertEquals(170.0, curve[120].x, 1e-8)
        assertEquals(70.0, curve[120].y, 1e-8)
        frame = VizEngine.slider(spec, frame, sliders[0], 0.0)
        val flat = frame.nodes.first { it.type == "scene" }.shapes.first { it.type == "plot" }.paths.single()
        assertTrue(flat.all { kotlin.math.abs(it.y - 70.0) < 1e-8 })
        frame = VizEngine.slider(spec, frame, sliders[0], 100.0)
        assertEquals(3.0, frame.state.number("a"), 0.0)
    }

    @Test
    fun actionsAreAtomicAndReadOldSnapshot() {
        val spec = parse(minimal("""{"a":1,"b":2}""", """{
            "swap":{"set":{"a":{"$":["get","b"]},"b":{"$":["get","a"]}}},
            "bad":{"set":{"a":5,"b":{"$":["div",1,0]}}},
            "type":{"set":{"a":"oops"}}
        }"""))
        val frame = VizEngine.frame(spec, spec.initialState)
        val changed = VizEngine.dispatch(spec, frame, "swap")
        assertEquals(2.0, changed.state.number("a"), 0.0)
        assertEquals(1.0, changed.state.number("b"), 0.0)
        assertThrows(VizException::class.java) { VizEngine.dispatch(spec, frame, "bad") }
        assertThrows(VizException::class.java) { VizEngine.dispatch(spec, frame, "type") }
        assertEquals(1.0, frame.state.number("a"), 0.0)
    }

    @Test
    fun evaluationShortCircuitsAndKeepsArrayInputImmutable() {
        val evaluator = VizEvaluator(JsonObject(emptyMap()))
        assertEquals(JsonPrimitive(3.0), evaluator.eval(expression("""{"$":["if",true,3,{"$":["div",1,0]}]}""")).let { JsonPrimitive(it.num()) })
        assertFalse(evaluator.eval(expression("""{"$":["and",false,{"$":["div",1,0]}]}""")).bool())
        assertTrue(evaluator.eval(expression("""{"$":["or",true,{"$":["div",1,0]}]}""")).bool())
        val input = expression("""{"$":["swap",[3,1,2],0,1]}""")
        assertEquals("[1,3,2]", evaluator.eval(input).toString())
        assertEquals("[3,1,2]", input.obj().value("$").arr()[1].toString())
        assertThrows(VizException::class.java) { evaluator.eval(expression("""{"$":["at",[1],3]}""")) }
        assertThrows(VizMathException::class.java) { evaluator.eval(expression("""{"$":["sqrt",-1]}""")) }
        assertEquals(1.0, evaluator.eval(expression("""{"$":["sin",1.5707963267948966]}""")).num(), 1e-12)
    }

    @Test
    fun strictJsonAndSchemaRejectUnsupportedInputs() {
        val source = minimal()
        val invalid = listOf(
            source.replace("\"version\":1", "\"version\":1,\"version\":1"),
            source.replace("\"version\":1", "\"version\":2"),
            source.replace("\"id\":\"test\"", "\"id\":\"test\",\"\\u0069d\":\"test\""),
            source.replace("\"state\":{}", "\"state\":{\"a\":01}"),
            source.replace("\"state\":{}", "\"state\":{\"a\":NaN}"),
            source.replace("\"title\":\"测试\"", "\"title\":\"line\nbreak\""),
            source.dropLast(1) + ",}",
            minimal(view = """[{"type":"text","text":{"$":["fetch","url"]}}]"""),
            minimal(view = """[{"type":"text","text":{"$":["get","missing"]}}]"""),
            minimal(view = """[{"type":"text","text":{"$":["var","x"]}}]"""),
            minimal(view = """[{"type":"text","text":"x","unknown":true}]"""),
            minimal(state = """{"values":[1,"x"]}"""),
            minimal(state = """{"x":${"[".repeat(65)}0${"]".repeat(65)}}"""),
            minimal(state = """{"x":"${"x".repeat(2049)}"}"""),
            " ".repeat(65537) + source,
        )
        invalid.forEach { text -> assertThrows(text.take(120), IllegalArgumentException::class.java) { parse(text) } }
    }

    @Test
    fun shapeBudgetsColorsAndPathsAreEnforced() {
        fun scene(shapes: String) = minimal(view = """[{"type":"scene","width":320,"height":200,"shapes":$shapes}]""")
        assertThrows(VizException::class.java) { parse(scene("""[{"type":"circle","cx":0,"cy":0,"r":5,"fill":"url(foo)"}]""")) }
        assertThrows(VizException::class.java) { parse(scene("""[{"type":"circle","cx":100001,"cy":0,"r":5}]""")) }
        val items = (1..64).joinToString(",")
        val repeated = """[{"type":"repeat","items":[$items],"shapes":[{"type":"repeat","items":[$items],"shapes":[{"type":"circle","cx":1,"cy":1,"r":1}]}]}]"""
        assertThrows(VizException::class.java) { parse(scene(repeated)) }
        val path = VizPath.parse("M10 20 l5 5 h10 v-5 z")
        assertEquals(VizPathCommand('L', 25.0, 20.0), path[3])
        assertEquals(VizPathCommand('Z', 10.0, 20.0), path[4])
        assertThrows(VizException::class.java) { VizPath.parse("M0 0 C1 1 2 2 3 3") }
        assertThrows(VizException::class.java) { VizPath.parse("M0") }
    }

    @Test
    fun plotBreaksAtInvalidMathWithoutDiscardingFrame() {
        val spec = parse(minimal(view = """[{"type":"scene","width":200,"height":200,"shapes":[
            {"type":"plot","originX":100,"originY":100,"scaleX":10,"scaleY":10,"xMin":-1,"xMax":1,
            "samples":5,"y":{"$":["div",1,{"$":["var","x"]}]},"stroke":"@primary"}]}]"""))
        val paths = VizEngine.frame(spec, spec.initialState).nodes.single().shapes.single().paths
        assertEquals(listOf(2, 2), paths.map { it.size })
    }

    @Test
    fun sessionCachePreservesStateResetEvictionAndContentIdentity() = runBlocking {
        val store = VizSessionStore(2)
        val source = fixture("register-counter")
        val first = store.load("one:${VizFenceScanner.digest(source)}", "one", source).session!!
        first.dispatch(first.frame.nodes.last())
        assertSame(first, store.load("one:${VizFenceScanner.digest(source)}", "one", source).session)
        assertEquals(1.0, first.frame.state.number("n"), 0.0)
        first.reset()
        assertEquals(0.0, first.frame.state.number("n"), 0.0)
        store.load("two", "two", source)
        store.load("three", "three", source)
        assertNull(store.cached("one:${VizFenceScanner.digest(source)}"))
        store.retainMessages(setOf("three"))
        assertNull(store.cached("two"))
        assertNotNull(store.cached("three"))
        store.clear()
        assertNull(store.cached("three"))
    }

    @Test
    fun promptIsAppendedOnceAndSettingsRemainBackwardCompatible() {
        val base = listOf(UIMessage.system("用户自定义\n模式注入"), UIMessage.user("问题"))
        assertSame(base, VizPrompt.appendToChat(base, false))
        val first = VizPrompt.appendToChat(base, true)
        assertEquals(first, VizPrompt.appendToChat(first, true))
        assertEquals("用户自定义\n模式注入", (base.first().parts.first() as UIMessagePart.Text).text)
        assertEquals(MessageRole.SYSTEM, VizPrompt.appendToChat(listOf(UIMessage.user("test")), true).first().role)
        val assistant = Json.decodeFromString<Assistant>("{}")
        assertFalse(assistant.enableInteractiveVisualization)
        assertTrue(Json.decodeFromString<DisplaySetting>("{}").enableInlineVisualizations)
        val configured = assistant.copy(enableInteractiveVisualization = true)
        val json = Json.encodeToJsonElement(configured)
        assertEquals(json, Json.encodeToJsonElement(Json.decodeFromJsonElement<Assistant>(json)))
        val display = DisplaySetting(enableInlineVisualizations = false)
        assertEquals(display, Json.decodeFromString<DisplaySetting>(Json.encodeToString(display)))
    }

    @Test
    fun v3ComponentsShareDerivedValuesAndTrackInputValidity() {
        val source = """
            {"op":"init","version":3,"id":"trace","state":{"a":5,"b":7,"step":0,"enabled":true}}
            {"op":"derived","seq":1,"id":"sum","value":{"$": ["add",{"$": ["get","a"]},{"$": ["get","b"]}]}}
            {"op":"data","seq":2,"id":"frames","value":[{"items":[-2,3]}],"inputKeys":["a"],"inputSnapshot":{"a":5}}
            {"op":"node","seq":3,"id":"number","slot":"parameters","data":{"type":"numberInput","label":"A","bind":"a","min":-100,"max":100}}
            {"op":"node","seq":4,"id":"steps","data":{"type":"stepper","bind":"step","count":{"$": ["len",{"$": ["data","frames"]}]},"resetOn":["a","b"]}}
            {"op":"node","seq":5,"id":"sequence","slot":"main","data":{"type":"sequence","mode":"bars","items":{"$": ["field",{"$": ["at",{"$": ["data","frames"]},{"$": ["get","step"]}]},"items"]}}}
            {"op":"node","seq":6,"id":"result","slot":"status","data":{"type":"status","text":{"$": ["derived","sum"]}}}
            {"op":"shape","seq":7,"id":"left","data":{"type":"labeledNode","x":10,"y":10,"w":100,"h":70,"label":"A","value":{"$": ["get","a"]}}}
            {"op":"shape","seq":8,"id":"right","data":{"type":"labeledNode","x":200,"y":10,"w":100,"h":70,"label":"合计","value":{"$": ["derived","sum"]}}}
            {"op":"shape","seq":9,"id":"wire","data":{"type":"connector","from":"left","to":"right"}}
            {"op":"node","seq":10,"id":"toggle","data":{"type":"toggle","label":"开关","bind":"enabled"}}
            {"op":"node","seq":11,"id":"select","data":{"type":"select","label":"B","bind":"b","options":[{"value":7,"label":"七"},{"value":8,"label":8}]}}
            {"op":"node","seq":12,"id":"table","data":{"type":"table","columns":[{"key":"n","label":"值"}],"rows":[{"n":{"$": ["derived","sum"]}}]}}
            {"op":"action","seq":13,"id":"explain","data":{"kind":"message","prompt":"解释结果","include":["a","b"]}}
        """.trimIndent() + "\n"
        val store = VizSessionStore()
        val result = store.advanceStream("trace", "message", source, false)
        assertNull(result.error)
        val session = result.session!!
        assertEquals("numberInput", session.frame.nodes.first().type)
        assertEquals("12", session.frame.nodes.first { it.type == "status" }.values.string("text"))
        assertEquals(8, session.frame.nodes.first { it.type == "scene" }.shapes.size)
        val cached = session.frame.nodes.first { it.type == "scene" }
        session.input(session.frame.nodes.first { it.type == "toggle" }, JsonPrimitive(false))
        assertSame(cached, session.frame.nodes.first { it.type == "scene" })
        val appended = source + """{"op":"appendData","seq":14,"id":"frames","items":[{"items":[3,-2]}]}""" + "\n"
        store.advanceStream("trace", "message", appended, false)
        session.input(session.frame.nodes.first { it.type == "stepper" }, JsonPrimitive(1))
        assertEquals(1, session.frame.state.value("step").int())
        session.input(session.frame.nodes.first { it.type == "numberInput" }, JsonPrimitive(9))
        assertEquals(0, session.frame.state.value("step").int())
        assertEquals("16", session.frame.nodes.first { it.type == "status" }.values.string("text"))
        assertTrue(session.frame.nodes.first { it.type == "stepper" }.values.value("stale").bool())
        val message = VizEngine.message(session.spec, session.frame, "explain")
        assertTrue(message.contains("解释结果") && message.contains("\"a\":9"))
        assertThrows(VizException::class.java) { VizEngine.dispatch(session.spec, session.frame, "explain") }
        assertThrows(VizException::class.java) {
            VizEngine.input(session.spec, session.frame, session.frame.nodes.first { it.type == "stepper" }, JsonPrimitive(1))
        }
        store.clear()
    }

    @Test
    fun scalarFormattingAndUnifiedDepthLimits() {
        assertEquals("0", JsonPrimitive(-0.0).displayText())
        assertEquals("0.0000001", JsonPrimitive(0.0000001).displayText())
        assertEquals("true", JsonPrimitive(true).displayText())
        assertThrows(VizException::class.java) { JsonArray(emptyList()).displayText() }
        parse(minimal(state = """{"x":${"[".repeat(30)}0${"]".repeat(30)}}"""))
        fun nested(depth: Int): JsonElement = (1..depth).fold(JsonPrimitive(1) as JsonElement) { value, _ ->
            buildJsonObject { put("$", JsonArray(listOf(JsonPrimitive("neg"), value))) }
        }
        validateExpression(nested(32), emptySet())
        assertThrows(VizException::class.java) { validateExpression(nested(33), emptySet()) }
        val spec = parse(minimal(state = """{"n":12}""", view = """[{"type":"text","text":{"$": ["get","n"]}}]"""))
        assertEquals("12", VizEngine.frame(spec, spec.initialState).nodes.single().values.string("text"))
        val recoverable = parse(minimal(state = """{"n":1}""",
            actions = """{"zero":{"set":{"n":0}}}""",
            view = """[{"type":"text","id":"number","text":{"$": ["div",1,{"$": ["get","n"]}]}}]"""))
        val old = VizEngine.frame(recoverable, recoverable.initialState)
        val next = VizEngine.dispatch(recoverable, old, "zero")
        assertEquals(0, next.state.value("n").int())
        assertEquals("1", next.nodes.single().values.string("text"))
        assertNotNull(next.nodes.single().error)
    }
}
