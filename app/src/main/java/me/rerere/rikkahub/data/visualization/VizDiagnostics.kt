package me.rerere.rikkahub.data.visualization

data class VizDiagnostic(
    val code: String,
    val message: String,
    val severity: String = "error",
    val physicalLine: Int? = null,
    val seq: Int? = null,
    val op: String? = null,
    val itemId: String? = null,
    val fieldPath: String? = null,
) {
    fun summary(): String = listOfNotNull(
        physicalLine?.let { "第${it}行" }, itemId, fieldPath, message,
    ).joinToString(" · ")
}

/** 导出保留 parser 原有文案，不追加源码、操作序号或修复提示。 */
fun diagnosticReport(diagnostics: List<VizDiagnostic>, count: Int, fallbackError: String?): String? {
    if (diagnostics.isNotEmpty()) {
        return buildString {
            append("部分内容未生成（$count）")
            diagnostics.forEach { append('\n'); append(it.summary()) }
        }
    }
    return fallbackError
}

enum class VizLifecycle { RECEIVING, COMPLETE, INCOMPLETE, CANCELLED, FAILED }
