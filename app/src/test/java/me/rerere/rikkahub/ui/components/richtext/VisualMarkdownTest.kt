package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.visualization.VizFenceScanner
import me.rerere.rikkahub.data.visualization.VizPrompt
import me.rerere.rikkahub.ui.components.visualization.VizSessionStore
import org.junit.Assert.*
import org.junit.Test

class VisualMarkdownTest {
    private val block = "```rikka-viz\n{\"version\":1,\"title\":\"计数器\"}\n```"

    @Test
    fun streamingUsesSourceClosureAndStableOrdinal() {
        val opening = "```rikka-viz\n{}\n"
        assertFalse(VizFenceScanner.scan(opening).single().fence.closed)
        assertFalse(VizFenceScanner.scan(opening + "``").single().fence.closed)
        val closed = VizFenceScanner.scan(opening + "```").single().fence
        assertTrue(closed.closed)
        assertEquals(closed, VizFenceScanner.scan(opening + "```\n\n后续文字").single().fence)
        assertEquals("{}", closed.code)
        assertTrue(VizFenceScanner.scan("~~~rikka-viz\r\n{}\r\n~~~~").single().fence.closed)
        assertFalse(VizFenceScanner.scan("````rikka-viz\n{}\n```").single().fence.closed)
        assertEquals("", VizFenceScanner.scan("```rikka-viz\n```").single().fence.code)
    }

    @Test
    fun nestedAndOrdinaryFencesNeverBecomeVisualizations() {
        listOf(
            "````markdown\n$block\n````",
            block.lines().joinToString("\n") { "> $it" },
            "- item\n\n" + block.lines().joinToString("\n") { "    $it" },
            block.lines().joinToString("\n") { "    $it" },
            "<pre>\n$block\n</pre>",
            "```json\n{\"rikka-viz\":true}\n```",
        ).forEach { assertTrue(it, VizFenceScanner.scan(it).isEmpty()) }
    }

    @Test
    fun sourceIsProtectedAndBothMarkdownPathsKeepClosure() = runBlocking {
        val native = prepareVisualMarkdown("原文\n\n$block", emptyList()) { it.replace("原文", "替换").replace("计数器", "损坏") }.prepared!!
        assertNull(native.document)
        assertTrue(native.preprocessed.contains("替换"))
        assertTrue(native.visualizationFences.values.single().code.contains("计数器"))
        val dom = prepareVisualMarkdown("<b>HTML</b>\n\n${block.dropLast(3)}", emptyList()) { it }.prepared!!
        assertNotNull(dom.document)
        assertFalse(dom.visualizationFences.values.single().closed)
        assertEquals(1, dom.document!!.select("pre code").size)
    }

    @Test
    fun referencesTablesMathAndFourBlockMessageBudgetSurvive() = runBlocking {
        val source = "<b>HTML</b>\n\n[label][ref]\n\n$block\n\n[ref]: https://example.com\n\n| a |\n| --- |\n| b |\n\n\\(x\\)"
        val prepared = prepareVisualMarkdown(source, emptyList()) { it }.prepared!!
        assertEquals("https://example.com", prepared.document!!.select("a").first { it.text() == "label" }.attr("href"))
        assertEquals(1, prepared.document.select("table").size)
        assertTrue(prepared.preprocessed.contains("\$x\$"))
        val multiple = prepareVisualMarkdown(List(5) { block }.joinToString("\n\n"), emptyList()) { it }.prepared!!
        assertEquals(4, multiple.visualizationFences.size)
        assertEquals(1, multiple.overflowFences.size)
        val laterPart = prepareVisualMarkdown(block, List(4) { block }) { it }.prepared!!
        assertTrue(laterPart.visualizationFences.isEmpty())
        assertFalse(laterPart.preprocessed.contains("计数器"))
        assertEquals(1, laterPart.overflowFences.size)
    }

    @Test
    fun speechKeepsProseAndTitleButNeverReadsIncompleteJson() {
        val text = VizFenceScanner.readableText("之前\n\n$block\n\n之后")
        assertTrue(text.contains("之前") && text.contains("之后") && text.contains("计数器"))
        assertFalse(text.contains("version"))
        assertFalse(VizFenceScanner.readableText(block.dropLast(3)).contains("version"))
    }

    @Test
    fun streamFenceKeepsAccumulatingWholeRecordsBeforeFenceClosure() = runBlocking {
        val initial = """{"op":"init","version":2,"id":"demo","title":"渐进图","width":320,"height":200}"""
        val partial = """{"op":"shape","seq":1,"id":"r1","data":{"type":"rect","x":1"""
        val source = "说明\n\n```rikka-viz-stream\n$initial\n$partial"
        val prepared = prepareVisualMarkdown(source, emptyList()) { it }.prepared!!
        val fence = prepared.visualizationFences.values.single()
        assertEquals("rikka-viz-stream", fence.language)
        assertFalse(fence.closed)
        assertTrue(fence.code.contains(initial))
        assertTrue(fence.code.contains(partial))
        val continued = source + ",\"y\":2,\"w\":8,\"h\":8}}\n"
        val next = prepareVisualMarkdown(continued, emptyList(), prepared.let {
            MarkdownPreparationState(source, it)
        }) { it }.prepared!!
        val nextFence = next.visualizationFences.values.single()
        assertFalse(nextFence.closed)
        assertTrue(nextFence.code.contains("\"w\":8"))
        assertEquals(fence.ordinal, nextFence.ordinal)
    }

    @Test
    fun normalMarkdownStillReceivesVisualRegexWhenNoVisualizationFenceExists() = runBlocking {
        val prepared = prepareVisualMarkdown("<b>plain</b> [link][r]\n\n[r]: https://example.com", emptyList()) {
            it.replace("plain", "changed")
        }
        assertTrue(prepared.prepared!!.preprocessed.contains("changed"))
        assertEquals("https://example.com", prepared.prepared!!.document!!.select("a").single().attr("href"))
    }

    @Test
    fun everyCharacterReachesSessionBeforeFenceClosesAndPartialClosingMarkerIsNotData() {
        val sourceFence = VizFenceScanner.scan(VizPrompt.text).single { it.fence.language == "rikka-viz-stream" }
        val markdown = "```rikka-viz-stream\n" + sourceFence.fence.code + "```\n后续文字"
        val store = VizSessionStore()
        val firstShapeEnd = markdown.indexOf('\n', markdown.indexOf("\"op\":\"shape\""))
        for (end in 1..markdown.length) {
            val fence = VizFenceScanner.scan(markdown.take(end)).singleOrNull()?.fence ?: continue
            if (fence.language != "rikka-viz-stream") continue
            val result = store.advanceStream("graph", "message", fence.code, fence.closed)
            assertNull("第 $end 字符：${result.error}", result.error)
            if (end == firstShapeEnd + 1) {
                assertFalse(fence.closed)
                assertEquals(3, result.session!!.frame.nodes.first { it.type == "scene" }.shapes.size)
            }
        }
        assertTrue(store.cached("graph")!!.done)
        assertEquals(3, store.cached("graph")!!.session!!.frame.nodes.first { it.type == "scene" }.shapes.size)
    }

    @Test
    fun nativeAndDomReuseDocumentForNewDataAndPreserveReferencesAndMathAfterClosing() = runBlocking {
        val stream = "```rikka-viz-stream\n{\"op\":\"init\"}\n"
        for (prefix in listOf("说明\n\n", "<b>说明</b>\n\n")) {
            val first = prepareVisualMarkdown(prefix + stream, emptyList()) { it }
            val second = prepareVisualMarkdown(prefix + stream + "{\"op\":\"shape\"}\n", emptyList(), first) { it }
            assertSame(first.prepared!!.astTree, second.prepared!!.astTree)
            assertSame(first.prepared.document, second.prepared.document)
            assertNotEquals(first.prepared.visualizationFences, second.prepared.visualizationFences)
            val slots = second.prepared.visualizationFences
            assertEquals(1, slots.size)
            second.prepared.document?.let { document ->
                assertTrue(document.select("pre code").single().classNames().contains("language-${slots.keys.single()}"))
            }
            val finalSource = prefix + stream + "```\n\n<label>结束</label>\n\n[label][ref]\n\n[ref]: https://example.com\n\n\\(x\\)"
            val closed = prepareVisualMarkdown(finalSource, emptyList(), second) { it }.prepared!!
            assertTrue(closed.visualizationFences.values.single().closed)
            assertTrue(closed.preprocessed.contains("\$x\$"))
            assertEquals("https://example.com", closed.document!!.select("a").single().attr("href"))
        }
    }

    @Test
    fun streamTokensAreNeverEnabledInsideOrdinaryCodeQuoteListOrHtml() {
        val stream = block.replace("rikka-viz", "rikka-viz-stream")
        listOf("````text\n$stream\n````", "<pre>\n$stream\n</pre>",
            stream.lines().joinToString("\n") { "> $it" },
            "- item\n\n" + stream.lines().joinToString("\n") { "    $it" },
        ).forEach { assertTrue(VizFenceScanner.scan(it).isEmpty()) }
        val init = """{"op":"init","version":2,"id":"cpu","title":"彩色图形","width":320,"height":200}"""
        val text = VizFenceScanner.readableText("前文\n\n```rikka-viz-stream\n$init\n{\"op\":")
        assertTrue(text.contains("彩色图形"))
        assertFalse(text.contains("\"op\""))
    }
}
