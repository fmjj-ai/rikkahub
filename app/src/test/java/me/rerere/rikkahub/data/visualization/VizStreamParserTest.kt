package me.rerere.rikkahub.data.visualization

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.estimateTokens
import me.rerere.rikkahub.ui.components.visualization.VizSessionStore
import org.junit.Assert.*
import org.junit.Test

class VizStreamParserTest {
    private val init = """{"op":"init","version":2,"id":"cpu","title":"CPU 数据通路","width":320,"height":200,"state":{"step":0},"actions":{}}"""
    private val rect = """{"type":"rect","x":20,"y":20,"w":80,"h":40,"fill":"#C9DDF2","stroke":"#667F99"}"""
    private fun shape(seq: Int, id: String = "r$seq", data: String = rect) =
        """{"op":"shape","seq":$seq,"id":"$id","data":$data}"""
    private fun done(seq: Int) = """{"op":"done","seq":$seq}"""
    private fun shapes(state: VizStreamState) = state.document!!.spec.view.first().value("shapes").arr()

    @Test
    fun completedLinesRenderWithoutWaitingForFenceOrDone() {
        var state = VizStreamParser.advance(VizStreamState(), "$init\n")
        assertNotNull(state.document)
        assertTrue(shapes(state).isEmpty())
        state = VizStreamParser.advance(state, shape(1) + "\n")
        assertEquals("r1", shapes(state).single().obj().string("id"))
        assertFalse(state.document!!.done)
        state = VizStreamParser.advance(state, shape(2) + "\n" + done(3) + "\n")
        assertEquals(2, shapes(state).size)
        assertTrue(state.document!!.done)
        assertNull(state.error)
    }

    @Test
    fun incompleteRecordsWaitForNewlineAndClosureFlushesOnlyCompleteFinalRecord() {
        var state = VizStreamParser.advance(VizStreamState(), init.take(27))
        state = VizStreamParser.advance(state, init.drop(27))
        assertNull(state.document)
        state = VizStreamParser.advance(state, "\n${shape(1)}")
        assertEquals(0, shapes(state).size)
        assertEquals(shape(1), state.pending)
        state = VizStreamParser.advance(state, "\r\n${shape(2)}")
        assertEquals(1, shapes(state).size)
        assertEquals(shape(2), state.pending)
        state = VizStreamParser.finish(state)
        assertEquals(2, shapes(state).size)
        assertFalse(state.failed)
        assertTrue(state.error!!.contains("done"))
        val finalDone = VizStreamParser.finish(VizStreamParser.advance(VizStreamState(), "$init\n${done(1)}"))
        assertTrue(finalDone.document!!.done)
        assertNull(finalDone.error)
        val malformed = VizStreamParser.finish(VizStreamParser.advance(VizStreamState(), "$init\n{\"op\":"))
        assertFalse(malformed.failed)
        assertTrue(malformed.diagnosticCount > 0)
        assertNotNull(malformed.document)
    }

    @Test
    fun everyCharacterChunkMatchesWholeDocumentIncludingUnicodeCrLfAndEscapes() {
        val data = """{"type":"text","x":20,"y":40,"text":"中文 😀 \\path\n换行","fill":"#30343B"}"""
        val source = "$init\r\n${shape(1, data = data)}\r\n${done(2)}\r\n"
        var chunked = VizStreamState()
        source.forEach { chunked = VizStreamParser.advance(chunked, it.toString()) }
        val whole = VizStreamParser.advance(VizStreamState(), source)
        assertNull(chunked.error)
        assertEquals(whole, chunked)
    }

    @Test
    fun badRecordKeepsEarlierGoodRecordsAndContinuesIndependentUpdates() {
        val bad = """{"op":"shape","seq":2,"id":"r2","data":{"type":"circle"}}"""
        val state = VizStreamParser.advance(VizStreamState(), "$init\n${shape(1)}\n$bad\n")
        assertFalse(state.failed)
        assertEquals(1, shapes(state).size)
        val recovered = VizStreamParser.advance(state, shape(3) + "\n")
        assertEquals(2, shapes(recovered).size)
        assertTrue(recovered.diagnosticCount > 0)
        val pending = VizStreamParser.advance(VizStreamParser.advance(VizStreamState(), "$init\n"), "{\"op\":")
        assertNull(pending.error)
        assertEquals(0, shapes(pending).size)
    }

    @Test
    fun sequenceAndIdsAndUnknownFieldsAreValidated() {
        assertTrue(VizStreamParser.advance(VizStreamState(), shape(1) + "\n").diagnosticCount > 0)
        for (bad in listOf(
            shape(1, data = rect.replace("80", "90")), shape(2, id = "r1"), init,
            shape(2, data = """{"type":"webview"}"""),
            shape(2, data = """{"type":"text","x":1,"y":1,"text":{"$":["var","x"]}}"""),
            shape(2).replace("\"seq\":2", "\"seq\":2,\"seq\":2"),
            shape(2).dropLast(1) + ",\"unknown\":true}",
        )) {
            val state = VizStreamParser.advance(VizStreamState(), "$init\n${shape(1)}\n$bad\n")
            assertFalse(bad, state.failed)
            assertTrue(bad, state.diagnosticCount > 0)
            assertEquals(1, shapes(state).size)
        }
        val titleOptional = VizStreamParser.advance(VizStreamState(), """{"op":"init","version":2,"id":"demo","width":320,"height":200}""" + "\n")
        assertEquals("demo", titleOptional.document!!.spec.title)
    }

    @Test
    fun shapeUpdatesKeepOtherNodesAndStableOrder() {
        var state = VizStreamParser.advance(VizStreamState(), "$init\n${shape(1)}\n${shape(2)}\n")
        state = VizStreamParser.advance(state,
            """{"op":"node","seq":3,"id":"caption","data":{"type":"text","text":"说明"}}""" + "\n" +
            """{"op":"replace","seq":4,"id":"r1","data":{"type":"circle","cx":60,"cy":60,"r":12}}""" + "\n" +
            """{"op":"replaceNode","seq":5,"id":"caption","data":{"type":"text","text":"新说明"}}""" + "\n")
        assertEquals("circle", shapes(state)[0].obj().string("type"))
        assertEquals("r2", shapes(state)[1].obj().string("id"))
        assertEquals("新说明", state.document!!.spec.view[1].string("text"))
        state = VizStreamParser.advance(state,
            """{"op":"remove","seq":6,"id":"r1"}""" + "\n" +
            """{"op":"removeNode","seq":7,"id":"caption"}""" + "\n")
        assertEquals("r2", shapes(state).single().obj().string("id"))
        assertEquals(1, state.document!!.spec.view.size)
    }

    @Test
    fun byteRecordAndShapeBudgetsCoverPartialAndCompletedLines() {
        val start = VizStreamParser.advance(VizStreamState(), "$init\n${shape(1)}\n")
        assertTrue(VizStreamParser.advance(start, "x".repeat(8193)).failed)
        assertTrue(VizStreamParser.advance(start, "x".repeat(8193) + "\n").failed)
        assertTrue(VizStreamParser.advance(start, "中".repeat(3000)).failed)
        var bytes = start
        repeat(9) { bytes = VizStreamParser.advance(bytes, " ".repeat(8000) + "\r\n") }
        assertTrue(bytes.failed)
        assertEquals(1, shapes(bytes).size)
        val exhausted = start.copy(records = VizStreamParser.MAX_RECORDS)
        assertTrue(VizStreamParser.advance(exhausted, done(2) + "\n").failed)
        val items = (1..64).joinToString(",")
        val excessive = """{"type":"repeat","items":[$items],"shapes":[{"type":"repeat","items":[$items],"shapes":[$rect]}]}"""
        assertTrue(VizStreamParser.advance(start, shape(2, data = excessive) + "\n").diagnosticCount > 0)
    }

    @Test
    fun doneRejectsTrailingDataInSameOrLaterChunkButAllowsWhitespace() {
        val complete = VizStreamParser.advance(VizStreamState(), "$init\n${done(1)}\n")
        assertTrue(complete.document!!.done)
        assertNull(VizStreamParser.advance(complete, "\n \r\n").error)
        val late = VizStreamParser.advance(complete, shape(2) + "\n")
        val same = VizStreamParser.advance(VizStreamState(), "$init\n${done(1)}\n${shape(2)}\n")
        assertTrue(late.diagnosticCount > 0 && same.diagnosticCount > 0)
        assertEquals(0, shapes(late).size)
        assertEquals(0, shapes(same).size)
    }

    @Test
    fun liveActionsSliderAndResetPersistAcrossNewRecordsAndCacheReload() {
        val store = VizSessionStore()
        val key = "chat:message:0:stream"
        var source = "$init\n"
        var result = store.advanceStream(key, "message", source, false)
        val session = result.session!!
        val lines = listOf(
            """{"op":"state","seq":1,"key":"a","value":1}""",
            """{"op":"action","seq":2,"id":"next","data":{"set":{"step":{"$":["add",{"$":["get","step"]},1]}}}}""",
            """{"op":"node","seq":3,"id":"button","data":{"type":"button","label":"下一步","action":"next"}}""",
            """{"op":"node","seq":4,"id":"slider","data":{"type":"slider","label":"参数","bind":"a","min":0,"max":3,"step":0.1}}""",
        )
        source += lines.joinToString("\n", postfix = "\n")
        result = store.advanceStream(key, "message", source, false)
        assertSame(session, result.session)
        session.dispatch(session.frame.nodes[1])
        session.updateSlider(session.frame.nodes[2], 2.1f)
        source += shape(5) + "\n"
        store.advanceStream(key, "message", source, false)
        assertEquals(1.0, session.frame.state.number("step"), 0.0)
        assertEquals(2.1, session.frame.state.number("a"), 1e-6)
        assertEquals(1, session.frame.nodes[0].shapes.size)
        assertSame(session, store.cached(key)!!.session)
        source += done(6)
        store.advanceStream(key, "message", source, false)
        assertFalse(store.cached(key)!!.done)
        val complete = store.advanceStream(key, "message", source, true)
        assertTrue(complete.done)
        assertNull(complete.error)
        assertSame(session, complete.session)
        session.reset()
        assertEquals(0.0, session.frame.state.number("step"), 0.0)
        assertEquals(1.0, session.frame.state.number("a"), 0.0)
        assertEquals(1, session.frame.nodes[0].shapes.size)
    }

    @Test
    fun incompatibleNewNodeDoesNotResetUserStateOrDiscardEarlierValidRecord() {
        val store = VizSessionStore()
        val key = "one"
        val button = """{"op":"node","seq":1,"id":"button","data":{"type":"button","label":"增加","action":"next"}}"""
        val initial = init.replace("\"actions\":{}", """"actions":{"next":{"set":{"step":2}}}""")
        var source = "$initial\n$button\n"
        val session = store.advanceStream(key, "m", source, false).session!!
        session.dispatch(session.frame.nodes[1])
        source += shape(2) + "\n" + """{"op":"node","seq":3,"id":"incompatible","data":{"type":"slider","label":"限制","bind":"step","min":0,"max":1,"step":1}}""" + "\n"
        val result = store.advanceStream(key, "m", source, false)
        assertNotNull(result.error)
        assertSame(session, result.session)
        assertEquals(2.0, session.frame.state.number("step"), 0.0)
        assertEquals(1, session.frame.nodes[0].shapes.size)
        assertEquals(2, session.frame.nodes.size)
    }

    @Test
    fun editsBranchesAndLruHaveIndependentLifecycle() {
        val store = VizSessionStore(2)
        val first = store.advanceStream("one", "m1", "$init\n${shape(1)}\n", false).session!!
        val edited = store.advanceStream("one", "m1", "$init\n", false).session!!
        assertNotSame(first, edited)
        assertEquals(0, edited.frame.nodes[0].shapes.size)
        val second = store.advanceStream("two", "m2", "$init\n", false).session!!
        assertNotSame(edited, second)
        store.advanceStream("three", "m3", "$init\n", false)
        assertNull(store.cached("one"))
        store.retainMessages(setOf("m3"))
        assertNull(store.cached("two"))
        assertNotNull(store.cached("three"))
        store.clear()
        assertNull(store.cached("three"))
    }

    @Test
    fun promptFixtureParsesAndReportsTokenEstimate() {
        val fence = VizFenceScanner.scan(VizPrompt.text).single { it.fence.language == "rikka-viz-stream" }.fence
        val state = VizStreamParser.finish(VizStreamParser.advance(VizStreamState(), fence.code))
        assertNull(state.error)
        assertTrue(state.document!!.done)
        val frame = VizEngine.frame(state.document!!.spec, state.document!!.spec.initialState)
        assertEquals(3, frame.nodes.first { it.type == "scene" }.shapes.size)
        assertEquals("#C9DDF2", frame.nodes.first { it.type == "scene" }.shapes.first().values.string("fill"))
        assertEquals("button", frame.nodes.last().type)
        val text = VizPrompt.text
        assertTrue(text.contains("马卡龙") && text.contains("莫兰迪"))
        println("VizPrompt: ${text.codePointCount(0, text.length)} code points, ${text.toByteArray(Charsets.UTF_8).size} UTF-8 bytes, ${text.estimateTokens()} estimated tokens")
    }

    @Test
    fun v3PublishesFirstWidgetAndRecoversWithIdempotentSequenceLedger() {
        val init = """{"op":"init","version":3,"id":"live","state":{"n":5}}"""
        val first = """{"op":"node","seq":1,"id":"input","slot":"parameters","data":{"type":"numberInput","label":"数值","bind":"n","min":0,"max":10}}"""
        val frames = mutableListOf<VizFrame>()
        var state = VizStreamParser.advance(VizStreamState(), "$init\n$first\n") { frames += VizEngine.frame(it, it.initialState) }
        assertFalse(state.document!!.done)
        assertEquals("numberInput", frames.last().nodes.single().type)
        state = VizStreamParser.advance(state, "$first\n{broken json}\n")
        assertEquals(1, state.document!!.spec.view.size)
        val last = """{"op":"node","seq":3,"id":"status","slot":"status","data":{"type":"status","text":true}}"""
        state = VizStreamParser.advance(state, "$last\n${done(4)}\n")
        assertTrue(state.document!!.done)
        assertFalse(state.failed)
        assertEquals(2, state.document!!.spec.view.size)
        assertTrue(state.diagnostics.any { it.code == "gap" })
        assertEquals("true", VizEngine.frame(state.document!!.spec, state.document!!.spec.initialState).nodes.last().values.string("text"))
        val tooFar = VizStreamParser.advance(VizStreamParser.advance(VizStreamState(), "$init\n"), shape(35) + "\n")
        assertTrue(tooFar.failed)
    }

    @Test
    fun diagnosticCallbackCollectsEveryErrorBeyondPreviewLimit() {
        val errors = (1..20).joinToString("\n") {
            """{"op":"unsupported","seq":$it}"""
        }
        val collected = mutableListOf<VizDiagnostic>()
        val state = VizStreamParser.advance(
            previous = VizStreamState(),
            fragment = "$init\n$errors\n",
            onDiagnostic = { collected += it },
        )
        assertEquals(20, state.diagnosticCount)
        assertEquals(16, state.diagnostics.size)
        assertEquals(20, collected.size)
        assertEquals(state.diagnostics, collected.takeLast(16))
    }

    @Test
    fun storeExportsCompleteDiagnosticsWithStableCount() = runBlocking {
        val errors = (1..20).joinToString("\n") {
            """{"op":"unsupported","seq":$it}"""
        }
        val store = VizSessionStore()
        val result = store.advanceStream("k", "m", "$init\n$errors\n", closed = true)
        assertEquals(20, result.diagnosticCount)
        assertEquals(16, result.diagnostics.size)
        val snapshot = store.diagnosticSnapshot("k", "m")
        assertNotNull(snapshot)
        assertEquals(20, snapshot!!.count)
        assertEquals(20, snapshot.items.size)
        assertEquals(result.diagnostics, snapshot.items.takeLast(16))
        store.clear()
    }

    @Test
    fun diagnosticsPreserveExactTextAndIncludeUnterminatedLastLine() {
        val collected = mutableListOf<VizDiagnostic>()
        val state = VizStreamParser.advance(VizStreamState(), "$init\n{broken json}")
        val final = VizStreamParser.finish(state, onDiagnostic = { collected += it })
        assertEquals(1, final.diagnosticCount)
        assertEquals(final.diagnostics, collected)
        assertEquals("部分内容未生成（1）\n${collected.single().summary()}",
            diagnosticReport(collected, 1, "不应额外拼接"))
        assertEquals("原始错误\n保留换行", diagnosticReport(emptyList(), 0, "原始错误\n保留换行"))
        assertNull(diagnosticReport(emptyList(), 0, null))
    }

    @Test
    fun rewrittenSourceRejectsStaleDiagnosticsEvenWithSameErrorCount() = runBlocking {
        val store = VizSessionStore()
        try {
            val first = "$init\n" + """{"op":"old_operation","seq":1}""" + "\n"
            val second = "$init\n" + """{"op":"new_operation","seq":1}""" + "\n"
            store.advanceStream("k", "m", first, true)
            store.advanceStream("k", "m", second, true)
            assertNull(store.diagnosticSnapshot("k", "m", first))
            assertNull(store.diagnosticSnapshot("k", "another-message", second))
            val current = store.diagnosticSnapshot("k", "m", second)!!
            assertEquals(1, current.count)
            assertTrue(current.items.single().summary().contains("new_operation"))
        } finally { store.clear() }
    }

    @Test
    fun fullScreenPinKeepsSessionUntilRelease() {
        val store = VizSessionStore(2)
        try {
            store.advanceStream("one", "m1", "$init\n", false)
            store.pin("one")
            store.advanceStream("two", "m2", "$init\n", false)
            store.advanceStream("three", "m3", "$init\n", false)
            assertNotNull(store.cached("one"))
            assertNull(store.cached("two"))
            store.unpin("one")
            store.retainMessages(setOf("m3"))
            assertNull(store.cached("one"))
        } finally { store.clear() }
    }
}
