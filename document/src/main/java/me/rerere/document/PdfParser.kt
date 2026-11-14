package me.rerere.document

import me.rerere.document.pdf.PdfFile
import me.rerere.document.pdf.PdfTextExtractor
import java.io.File

object PdfParser {
    fun parserPdf(file: File): String {
        val document = PdfFile(file.readBytes())
        val extractor = PdfTextExtractor(document)
        val pages = document.pages()
        check(pages.isNotEmpty()) { "No pages found in PDF" }
        val result = StringBuilder()
        pages.forEachIndexed { i, page ->
            result.append("---")
            result.append("Page ${i + 1}:\n")
            // 单页损坏不丢弃其余页；格式保持与既有附件提示一致。
            result.append(try {
                extractor.extract(page)
            } catch (e: Exception) {
                "[Failed to extract text from this page: ${e.message}]"
            })
            result.appendLine()
        }
        return result.toString()
    }
}
