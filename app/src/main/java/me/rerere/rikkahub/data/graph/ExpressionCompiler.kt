package me.rerere.rikkahub.data.graph

import net.objecthunter.exp4j.Expression
import net.objecthunter.exp4j.ExpressionBuilder

class CompiledExpression internal constructor(private val expression: Expression) {
    fun fork(): CompiledExpression = CompiledExpression(Expression(expression))
    // 每个采样任务持有独立求值器，避免 exp4j 可变变量表被并发访问。
    fun evaluate(x: Double): Double = try {
        expression.setVariable("x", x).evaluate().let { if (it.isFinite()) it else Double.NaN }
    } catch (_: ArithmeticException) {
        Double.NaN
    }
}

object ExpressionCompiler {
    private val functions = setOf("sin", "cos", "tan", "asin", "acos", "atan", "sqrt", "abs", "exp", "log", "log10")
    private val aliases = mapOf("arcsin" to "asin", "arccos" to "acos", "arctan" to "atan", "ln" to "log", "π" to "pi")

    fun normalize(source: String): String {
        requireGraph(source.isNotBlank() && source.length <= 256, "RESOURCE_LIMIT", "表达式需要 1–256 字符")
        val output = StringBuilder()
        var index = 0
        var depth = 0
        var expectOperand = true
        while (index < source.length) {
            val char = source[index]
            when {
                char.isWhitespace() -> { index++; output.append(' ') }
                char.isLetter() -> {
                    requireGraph(expectOperand, "INVALID_EXPRESSION", "相邻项之间需要显式运算符")
                    val start = index++
                    while (index < source.length && source[index].isLetterOrDigit()) index++
                    val token = source.substring(start, index).lowercase()
                    val name = aliases[token] ?: token
                    requireGraph(name in functions || name in setOf("x", "pi", "e"),
                        "INVALID_EXPRESSION", "未知的函数或变量：$token（仅允许变量 x）")
                    if (name in functions) {
                        val next = source.drop(index).firstOrNull { !it.isWhitespace() }
                        requireGraph(next == '(', "INVALID_EXPRESSION", "函数 $token 需要括号")
                    }
                    expectOperand = name in functions
                    output.append(name)
                }
                char.isDigit() || char == '.' -> {
                    requireGraph(expectOperand, "INVALID_EXPRESSION", "相邻项之间需要显式运算符")
                    val start = index++
                    while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
                    if (index < source.length && source[index] in "eE") {
                        val exponent = index
                        var end = index + 1
                        if (end < source.length && source[end] in "+-") end++
                        val digits = end
                        while (end < source.length && source[end].isDigit()) end++
                        if (end > digits) index = end else index = exponent
                    }
                    val token = source.substring(start, index)
                    requireGraph(token.toDoubleOrNull()?.isFinite() == true,
                        "INVALID_EXPRESSION", "无效或过大的数值：$token")
                    output.append(token)
                    expectOperand = false
                }
                char in "+-*/^()" || char == '−' -> {
                    when (char) {
                        '(' -> requireGraph(expectOperand, "INVALID_EXPRESSION", "括号前需要显式运算符")
                        ')' -> {
                            requireGraph(!expectOperand, "INVALID_EXPRESSION", "括号内缺少表达式")
                            expectOperand = false
                        }
                        '+', '-', '−' -> expectOperand = true
                        else -> {
                            requireGraph(!expectOperand, "INVALID_EXPRESSION", "运算符前缺少表达式")
                            expectOperand = true
                        }
                    }
                    if (char == '(') depth++
                    if (char == ')') depth--
                    requireGraph(depth in 0..24, "INVALID_EXPRESSION", "括号不匹配或嵌套超过 24 层")
                    output.append(if (char == '−') '-' else char)
                    index++
                }
                else -> throw GraphValidationException("INVALID_EXPRESSION", "不支持的字符：$char；请使用显式乘号和普通数学表达式")
            }
        }
        requireGraph(depth == 0, "INVALID_EXPRESSION", "括号不匹配")
        requireGraph(!expectOperand, "INVALID_EXPRESSION", "表达式不完整")
        return output.toString().trim()
    }

    fun compile(source: String): CompiledExpression {
        val normalized = normalize(source)
        return try {
            val expression = ExpressionBuilder(normalized).variable("x").implicitMultiplication(false).build()
            val validation = expression.validate(false)
            requireGraph(validation.isValid, "INVALID_EXPRESSION", "表达式语法无效，请检查运算符与参数")
            CompiledExpression(expression)
        } catch (error: GraphValidationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            throw GraphValidationException("INVALID_EXPRESSION", "表达式语法无效，请检查括号、运算符与函数参数")
        }
    }
}
