package com.example.paddleocr

import com.example.paddleocr.data.OcrResult
import com.example.paddleocr.data.ResultRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 结果导出：TXT / Markdown / DOCX。
 * 生成到 cacheDir，由调用方通过 SAF 保存或分享。
 */
object Exporter {

    fun buildTxt(result: OcrResult): File =
        newFile("txt").apply { writeText(plainText(result), Charsets.UTF_8) }

    internal fun plainText(result: OcrResult): String =
        PlainText.convert(result)

    fun buildMarkdown(result: OcrResult): File =
        newFile("md").apply { writeText(markdownText(result), Charsets.UTF_8) }

    internal fun markdownText(result: OcrResult): String {
        val source = result.markdown?.takeIf { it.isNotBlank() } ?: result.fullText
        return FormattedContent.parse(source).mapNotNull { block ->
            when (block) {
                is FormattedBlock.Text -> MdToHtml.sanitize(block.source)
                    .trim().takeIf { it.isNotBlank() }
                is FormattedBlock.Table -> tableToMarkdown(block.table)
            }
        }.joinToString("\n\n").trim()
    }

    fun buildDocx(result: OcrResult): File {
        val f = newFile("docx")
        val source = result.markdown?.takeIf { it.isNotBlank() } ?: result.fullText
        val blocks = FormattedContent.parse(source)

        val documentXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
            append("<w:body>")
            append("<w:p><w:r><w:t xml:space=\"preserve\">OCR识别结果</w:t></w:r></w:p>")
            blocks.forEach { block ->
                when (block) {
                    is FormattedBlock.Text -> appendDocxText(block.source)
                    is FormattedBlock.Table -> appendDocxTable(block.table)
                }
            }
            append("</w:body></w:document>")
        }

        val entries = linkedMapOf(
            "[Content_Types].xml" to CONTENT_TYPES,
            "_rels/.rels" to RELS,
            "word/_rels/document.xml.rels" to DOCUMENT_RELS,
            "word/document.xml" to documentXml
        )
        ZipOutputStream(f.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return f
    }

    private fun tableToMarkdown(table: MarkdownTable): String = buildString {
        fun cells(values: List<String>) = values.joinToString(" | ") {
            it.replace("|", "\\|").replace("\n", " ")
        }
        append("| ").append(cells(table.headers)).appendLine(" |")
        append("|").append(table.headers.joinToString("|") { "---" }).appendLine("|")
        table.rows.forEach { row ->
            append("| ").append(cells(row)).appendLine(" |")
        }
    }.trim()

    private fun StringBuilder.appendDocxText(source: String) {
        source.lineSequence().forEach { raw ->
            val sanitized = MdToHtml.sanitize(raw).trim()
            if (sanitized.isEmpty()) return@forEach
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(sanitized)
            val unordered = Regex("^[-*+]\\s+(.*)$").find(sanitized)
            val ordered = Regex("^(\\d+)\\.\\s+(.*)$").find(sanitized)
            when {
                heading != null -> appendDocxParagraph(
                    PlainText.clean(heading.groupValues[2]),
                    bold = true,
                    size = (34 - heading.groupValues[1].length * 2).coerceAtLeast(24)
                )
                unordered != null -> appendDocxParagraph("• ${PlainText.clean(unordered.groupValues[1])}")
                ordered != null -> appendDocxParagraph(
                    "${ordered.groupValues[1]}. ${PlainText.clean(ordered.groupValues[2])}"
                )
                else -> appendDocxParagraph(PlainText.clean(sanitized))
            }
        }
    }

    private fun StringBuilder.appendDocxParagraph(text: String, bold: Boolean = false, size: Int? = null) {
        if (text.isBlank()) return
        append("<w:p><w:r>")
        if (bold || size != null) {
            append("<w:rPr>")
            if (bold) append("<w:b/>")
            if (size != null) append("<w:sz w:val=\"").append(size).append("\"/>")
            append("</w:rPr>")
        }
        append("<w:t xml:space=\"preserve\">")
            .append(xmlEscape(text))
            .append("</w:t></w:r></w:p>")
    }

    private fun StringBuilder.appendDocxTable(table: MarkdownTable) {
        val colCount = maxOf(1, table.headers.size)
        append("<w:tbl>")
        append("<w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>")
        append("<w:tblGrid>")
        repeat(colCount) { append("<w:gridCol w:w=\"2200\"/>") }
        append("</w:tblGrid>")

        fun appendRow(cells: List<String>) {
            append("<w:tr>")
            for (i in 0 until colCount) {
                val cell = cells.getOrElse(i) { "" }
                append("<w:tc>")
                append("<w:tcPr><w:tcW w:w=\"2200\" w:type=\"dxa\"/></w:tcPr>")
                append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    .append(xmlEscape(cell))
                    .append("</w:t></w:r></w:p>")
                append("</w:tc>")
            }
            append("</w:tr>")
        }

        appendRow(table.headers)
        table.rows.forEach { appendRow(it) }
        append("</w:tbl>")
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun newFile(ext: String): File {
        val name = "OCR_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext"
        return ResultRepository.cacheFile(name)
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
}
