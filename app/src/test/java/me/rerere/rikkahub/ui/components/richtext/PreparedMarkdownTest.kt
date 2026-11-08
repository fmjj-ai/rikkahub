package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedMarkdownTest {
    @Test
    fun codeRangesAreNotShiftedByAnEarlierFormulaReplacement() = runBlocking {
        val source = "\\(x\\)\n\n```kotlin\nval text = \"\\[literal\\]\"\n<div>code</div>\n```\n"
        val prepared = prepareMarkdown(source)
        assertEquals(source, prepared.source)
        assertTrue(prepared.preprocessed.startsWith("\$x\$"))
        assertTrue(prepared.preprocessed.contains("\\[literal\\]"))
        assertNull(prepared.document)
    }

    @Test
    fun inlineIndentedAndUnclosedFencedCodeStayLiteral() = runBlocking {
        for (source in listOf(
            "`\\(inline\\)`",
            "    \\[indented\\]\n",
            "````\n\\[unclosed\\]\n<div>code</div>\n",
            "~~~\n\\[unclosed\\]\n<div>code</div>\n",
        )) {
            val prepared = prepareMarkdown(source)
            assertEquals(source, prepared.preprocessed)
            assertNull(prepared.document)
        }
    }

    @Test
    fun displayMathKeepsExplicitLatexLineBreaks() = runBlocking {
        val source = "\\[\n  a + b \\\\ c\n  + d\n\\]"
        val prepared = prepareMarkdown(source)
        assertEquals("\$\$a + b \\\\ c + d\$\$", prepared.preprocessed)
        assertNull(prepared.document)
    }

    @Test
    fun htmlAndNativeResultsAlwaysBelongToTheSameSource() = runBlocking {
        val sources = listOf("**native**", "<div>html</div>\n\n**markdown**", "native again")
        val results = sources.map { prepareMarkdown(it) }
        assertNull(results[0].document)
        assertNotNull(results[1].document)
        assertTrue(results[1].document!!.body().text().contains("html"))
        assertTrue(results[1].document!!.select("strong").any { it.text() == "markdown" })
        assertNull(results[2].document)
        assertEquals(sources, results.map { it.source })
    }

    @Test
    fun tablesReuseCellInlineTreesAndHtmlTablesKeepTheirStructure() = runBlocking {
        val native = prepareMarkdown("| **header** | code |\n| --- | --- |\n| *value* | `x` |")
        val cells = descendants(native.astTree).filter { it.type == GFMTokenTypes.CELL }
        assertEquals(4, cells.size)
        assertTrue(cells.all { it.children.isNotEmpty() })
        val html = prepareMarkdown("| header |\n| --- |\n| a<br>b |")
        assertEquals(1, html.document!!.select("th").size)
        assertEquals(1, html.document!!.select("td").size)
        assertTrue(html.document!!.select("td").text().contains("a"))
    }

    @Test
    fun citationsAndReferenceLinksKeepTheWholeDocumentLinkMap() = runBlocking {
        val prepared = prepareMarkdown("<div>html</div>\n\n[citation,example.com](abcdef) and [label][ref]\n\n[ref]: https://example.com")
        val document = prepared.document!!
        assertEquals("abcdef", document.select("a").first { it.text() == "citation,example.com" }.attr("href"))
        assertEquals("https://example.com", document.select("a").first { it.text() == "label" }.attr("href"))
    }

    @Test
    fun preparationInstancesCanRunConcurrentlyWithoutSharingHtmlBuilders() = runBlocking {
        val results = (1..8).map { index -> async { prepareMarkdown("<b>message $index</b>") } }.awaitAll()
        results.forEachIndexed { index, result ->
            assertEquals("message ${index + 1}", result.document!!.body().text())
        }
    }

    @Test
    fun failureDoesNotStopNextPreparationAndCancellationIsNotPublished() = runBlocking {
        val failure = prepareMarkdownState("failed") { throw IllegalArgumentException("test failure") }
        assertEquals("failed", failure.source)
        assertNull(failure.prepared)
        val next = prepareMarkdown("next")
        assertSame(next, prepareMarkdownState("next") { next }.prepared)
        val entered = CompletableDeferred<Unit>()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            prepareMarkdownState("old") { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
    }

    private fun descendants(node: ASTNode): List<ASTNode> = listOf(node) + node.children.flatMap(::descendants)
}
