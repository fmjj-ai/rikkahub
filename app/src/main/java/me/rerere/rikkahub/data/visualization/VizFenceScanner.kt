package me.rerere.rikkahub.data.visualization

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.security.MessageDigest

/** 复用 Markdown 语法树，只提取顶层围栏，保留列表、引用和普通代码块的语义。 */
object VizFenceScanner {
    /** 仅续接由 AST 确认的顶层活动围栏；可能出现关闭标记时交还 AST。 */
    fun advance(source: String, previousSource: String, previous: List<VizSourceFence>): List<VizSourceFence> {
        val active = previous.lastOrNull() ?: return scan(source)
        if (active.fence.closed || !active.fence.language.equals("rikka-viz-stream", true) ||
            !source.startsWith(previousSource)) return scan(source)
        val newline = previousSource.indexOf('\n', active.start)
        if (newline < 0) return scan(source)
        val opening = previousSource.substring(active.start, newline).trimStart()
        val marker = opening.firstOrNull() ?: return scan(source)
        // 重新检查旧尾行及新增部分，涵盖跨 chunk 的反引号和波浪线关闭标记。
        val tailStart = maxOf(newline + 1, previousSource.lastIndexOf('\n') + 1)
        if (source.substring(tailStart).lineSequence().any { it.trimStart(' ').startsWith(marker) }) return scan(source)
        val fence = active.copy(end = source.length,
            fence = active.fence.copy(code = source.substring(newline + 1), closed = false))
        return previous.dropLast(1) + fence
    }

    fun scan(source: String): List<VizSourceFence> {
        if (!source.contains("rikka-viz", ignoreCase = true)) return emptyList()
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        return tree.children.filter { it.type == MarkdownElementTypes.CODE_FENCE }.mapNotNull { node ->
            val languageNode = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                ?: return@mapNotNull null
            val language = source.substring(languageNode.startOffset, languageNode.endOffset).trim()
            if (!language.equals("rikka-viz", ignoreCase = true) &&
                !language.equals("rikka-viz-stream", ignoreCase = true)) return@mapNotNull null
            val closing = node.children.firstOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_END }
            val firstNewline = source.indexOf('\n', node.startOffset)
            val streaming = language.equals("rikka-viz-stream", ignoreCase = true)
            var codeEnd = closing?.startOffset ?: node.endOffset
            // AST 可将末尾换行留在围栏节点之外；NDJSON 必须保留这个提交边界。
            if (streaming && closing == null) {
                while (codeEnd < source.length && source[codeEnd] in "\r\n") codeEnd++
            }
            val code = if (firstNewline in node.startOffset until codeEnd) {
                val body = source.substring(firstNewline + 1, codeEnd)
                if (streaming) {
                    // 模型逐 token 写入关闭标记时，一个或两个反引号还不是 JSON 数据。
                    val opening = source.substring(node.startOffset, firstNewline).trimStart()
                    val marker = opening.first()
                    val markerLength = opening.takeWhile { it == marker }.length
                    val tailStart = body.lastIndexOf('\n') + 1
                    val tail = body.substring(tailStart)
                    val trimmed = tail.trimStart(' ')
                    if (closing == null && tail.length - trimmed.length <= 3 && trimmed.isNotEmpty() &&
                        trimmed.length < markerLength && trimmed.all { it == marker }) body.substring(0, tailStart)
                    else body
                } else body.trimEnd('\r', '\n', ' ')
            } else ""
            VizSourceFence(node.startOffset, node.endOffset, VizFence(0, language, code, closing != null))
        }.mapIndexed { ordinal, item -> item.copy(fence = item.fence.copy(ordinal = ordinal)) }
    }

    fun digest(code: String): String = MessageDigest.getInstance("SHA-256")
        .digest(code.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 朗读和通知使用图名；历史、导出和模型上下文保留原始正文。 */
    fun readableText(source: String): String {
        val fences = scan(source)
        if (fences.isEmpty()) return source
        return buildString {
            var cursor = 0
            fences.forEach { item ->
                append(source, cursor, item.start)
                val title = runCatching {
                    val fence = item.fence
                    val candidate = if (fence.language.equals("rikka-viz-stream", true)) {
                        if (!fence.code.contains('\n') && !fence.closed) return@runCatching null
                        fence.code.lineSequence().firstOrNull { it.isNotBlank() } ?: return@runCatching null
                    } else if (fence.closed) fence.code else return@runCatching null
                    VizJsonReader(candidate).read().obj()["title"]?.str()?.take(256)
                }.getOrNull()
                append(title ?: "交互式可视化")
                cursor = item.end
            }
            append(source, cursor, source.length)
        }
    }
}
