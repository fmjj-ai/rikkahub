package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** AST 和 DOM 属于同一输入快照，渲染期间只读；不缓存主题、字号或回调。 */
internal data class PreparedMarkdown(
    val source: String,
    val preprocessed: String,
    val astTree: ASTNode,
    val document: Document?,
)

internal data class MarkdownPreparationState(val source: String, val prepared: PreparedMarkdown?)

/** 仅导出作用域提供预准备结果，导出结束即释放，不形成全局长驻 AST 缓存。 */
internal val LocalPreparedMarkdown = staticCompositionLocalOf<Map<String, PreparedMarkdown>> { emptyMap() }

private val INLINE_LATEX = Regex("""\\\((.+?)\\\)""")
private val BLOCK_LATEX = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)
private val LATEX_LINE_BREAK = Regex("""[ \t]*\r?\n[ \t]*""")

internal suspend fun prepareMarkdown(source: String): PreparedMarkdown = withContext(Dispatchers.Default) {
    val flavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    val parser = MarkdownParser(flavour)
    val originalTree = parser.buildMarkdownTreeFromString(source)
    val preprocessed = preprocessLatex(source, originalTree)
    // 仅公式转换确实改变源文时重建最终树；原文树保护所有语法上的代码区域，避免另造围栏解析器。
    val tree = if (preprocessed == source) originalTree else parser.buildMarkdownTreeFromString(preprocessed)
    val document = if (tree.containsHtmlOutsideCode()) {
        Jsoup.parse(HtmlGenerator(preprocessed, tree, flavour).generateHtml())
    } else null
    PreparedMarkdown(source, preprocessed, tree, document)
}

internal suspend fun prepareMarkdownState(
    source: String,
    prepare: suspend (String) -> PreparedMarkdown = ::prepareMarkdown,
): MarkdownPreparationState = try {
    MarkdownPreparationState(source, prepare(source))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    error.printStackTrace()
    MarkdownPreparationState(source, null)
}

private fun ASTNode.isCode(): Boolean = type == MarkdownElementTypes.CODE_FENCE ||
    type == MarkdownElementTypes.CODE_BLOCK || type == MarkdownElementTypes.CODE_SPAN

private fun ASTNode.containsHtmlOutsideCode(): Boolean {
    if (isCode()) return false
    if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) return true
    return children.any { it.containsHtmlOutsideCode() }
}

private fun preprocessLatex(source: String, tree: ASTNode): String {
    if (!source.contains("\\(") && !source.contains("\\[")) return source
    val codeRanges = mutableListOf<IntRange>()
    fun collect(node: ASTNode) {
        if (node.isCode()) {
            val start = node.startOffset.coerceIn(0, source.length)
            val end = node.endOffset.coerceIn(start, source.length)
            if (end > start) codeRanges.add(start until end)
        } else {
            node.children.forEach(::collect)
        }
    }
    collect(tree)
    var cursor = 0
    val converted = buildString(source.length) {
        for (range in codeRanges.sortedBy { it.first }) {
            if (range.first < cursor) continue
            append(convertLatex(source.substring(cursor, range.first)))
            append(source, range.first, range.last + 1)
            cursor = range.last + 1
        }
        append(convertLatex(source.substring(cursor)))
    }
    return if (converted == source) source else converted
}

private fun convertLatex(source: String): String {
    val inline = INLINE_LATEX.replace(source) { "\$" + it.groupValues[1] + "\$" }
    return BLOCK_LATEX.replace(inline) {
        "\$\$" + it.groupValues[1].trim().replace(LATEX_LINE_BREAK, " ") + "\$\$"
    }
}
