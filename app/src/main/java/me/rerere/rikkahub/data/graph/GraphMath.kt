package me.rerere.rikkahub.data.graph

// 只排版本工具允许的数学语法；不执行标签中的代码或修改真正用于求值的表达式。
fun graphExpressionLatex(source: String): String? = runCatching {
    val normalized = ExpressionCompiler.normalize(source)
    ExpressionCompiler.compile(normalized)
    GraphLatexParser(normalized).parse()
}.getOrNull()

private class GraphLatexParser(source: String) {
    private val tokens = Regex("""(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?|[a-z]+\d*|[^\s]""").findAll(source)
        .map { it.value }.toList()
    private var index = 0
    private data class Term(val latex: String, val precedence: Int = 10)

    fun parse(): String {
        val result = expression(0).latex
        require(index == tokens.size)
        return result
    }

    private fun grouped(term: Term, precedence: Int, equal: Boolean = false): String =
        if (term.precedence < precedence || equal && term.precedence == precedence) {
            "\\left(${term.latex}\\right)"
        } else term.latex

    private fun expression(minimum: Int): Term {
        val token = tokens[index++]
        var left = when {
            token == "+" || token == "-" -> Term(token + grouped(expression(3), 3), 3)
            token == "(" -> expression(0).also { require(tokens[index++] == ")") }
            token.first().isLetter() && token !in setOf("x", "pi", "e") -> {
                require(tokens[index++] == "(")
                val argument = expression(0).latex
                require(tokens[index++] == ")")
                Term(when (token) {
                    "sqrt" -> "\\sqrt{$argument}"
                    "abs" -> "\\left|$argument\\right|"
                    "exp" -> "e^{$argument}"
                    else -> {
                        val name = when (token) {
                            "asin" -> "\\arcsin"
                            "acos" -> "\\arccos"
                            "atan" -> "\\arctan"
                            "log" -> "\\ln"
                            "log10" -> "\\log_{10}"
                            else -> "\\$token"
                        }
                        "$name\\left($argument\\right)"
                    }
                }, if (token == "exp") 4 else 10)
            }
            token == "pi" -> Term("\\pi")
            else -> {
                val scientific = Regex("""(.+)[eE]([+-]?\d+)""").matchEntire(token)
                Term(if (scientific == null) token else
                    "${scientific.groupValues[1]}\\times 10^{${scientific.groupValues[2]}}",
                    if (scientific == null) 10 else 2)
            }
        }
        while (index < tokens.size) {
            val operator = tokens[index]
            val precedence = when (operator) { "+", "-" -> 1; "*", "/" -> 2; "^" -> 4; else -> -1 }
            if (precedence < minimum) break
            index++
            val right = expression(if (operator == "^") precedence else precedence + 1)
            left = Term(when (operator) {
                "/" -> "\\frac{${left.latex}}{${right.latex}}"
                "^" -> "${grouped(left, precedence, equal = true)}^{${right.latex}}"
                "*" -> "${grouped(left, precedence)}\\cdot ${grouped(right, precedence)}"
                else -> "${grouped(left, precedence)}$operator${grouped(right, precedence, operator == "-") }"
            }, precedence)
        }
        return left
    }
}

data class GraphTextPart(val text: String, val math: Boolean = false)

// 新标签可直接使用行内 LaTeX；旧记录中的 y=表达式和坐标也按同一规则排版。
fun graphTextParts(text: String): List<GraphTextPart> {
    val parts = mutableListOf<GraphTextPart>()
    val pattern = Regex("""\$\$[\s\S]*?\$\$|\$[^$\n]+\$|\\\([\s\S]*?\\\)|\\\[[\s\S]*?\\\]|[A-Za-z0-9π√(][A-Za-z0-9π√\s.+*/^()=,−⁰¹²³⁴⁵⁶⁷⁸⁹-]*""")
    var end = 0
    pattern.findAll(text).forEach { match ->
        if (match.range.first > end) parts.add(GraphTextPart(text.substring(end, match.range.first)))
        val raw = match.value.trimEnd()
        val latex = when {
            raw.startsWith('$') || raw.startsWith("\\(") || raw.startsWith("\\[") -> raw
            else -> {
                val normalized = raw.replace("²", "^2").replace("³", "^3")
                    .replace(Regex("""√(\d+(?:\.\d+)?)"""), "sqrt($1)")
                val equation = normalized.split('=')
                if (equation.size == 2 && equation.first().trim() == "y") {
                    graphExpressionLatex(equation.last())?.let { "y=$it" }
                } else {
                    val coordinates = normalized.removePrefix("(").removeSuffix(")").split(',')
                    if (coordinates.size == 2) {
                        val values = coordinates.map { graphExpressionLatex(it) }
                        if (values.all { it != null }) "\\left(${values.joinToString(", ")}\\right)" else null
                    } else graphExpressionLatex(normalized)
                }
            }
        }
        parts.add(GraphTextPart(latex ?: raw, latex != null))
        if (match.value.length > raw.length) parts.add(GraphTextPart(" "))
        end = match.range.last + 1
    }
    if (end < text.length) parts.add(GraphTextPart(text.substring(end)))
    return parts
}
