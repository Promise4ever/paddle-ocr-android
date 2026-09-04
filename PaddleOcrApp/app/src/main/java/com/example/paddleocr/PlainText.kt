package com.example.paddleocr

import com.example.paddleocr.data.OcrResult

/**
 * 把 OCR 结果转换为适合复制/保存 TXT 的纯文本：
 * - 去掉 HTML 标签
 * - 去掉 Markdown 语法（标题、列表、粗体、链接、管道表格等）
 * - 表格以制表符分隔，便于粘贴到 Excel / 记事本阅读
 */
object PlainText {

    fun convert(result: OcrResult): String {
        val source = result.markdown?.takeIf { it.isNotBlank() } ?: result.fullText
        return FormattedContent.parse(source).mapNotNull { block ->
            when (block) {
                is FormattedBlock.Text -> clean(block.source).takeIf { it.isNotBlank() }
                is FormattedBlock.Table -> tableText(block.table)
            }
        }.joinToString("\n\n").trim()
    }

    internal fun clean(md: String): String {
        val sb = StringBuilder()
        MdToHtml.sanitize(md).lineSequence().forEach { raw ->
            var line = raw.trim()
            if (line.isEmpty()) return@forEach
            line = decodeEntities(line)
            if (line.startsWith("> ")) line = line.removePrefix("> ").trim()
            line = line.replace(Regex("""^#{1,6}\s+"""), "")
            line = line.replace(Regex("""^[-*+]\s+"""), "")
            line = line.replace(Regex("""^\d+\.\s+"""), "")
            line = line.replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            line = line.replace(Regex("""\*([^*]+)\*"""), "$1")
            line = line.replace(Regex("""`([^`]+)`"""), "$1")
            line = line.replace(Regex("""\[([^\]]+)\]\(([^)]+)\)"""), "$1")
            if (isDelimiter(line)) return@forEach
            sb.appendLine(line)
        }
        return sb.toString().trim()
    }

    private fun tableText(table: MarkdownTable): String = buildString {
        appendLine(table.headers.joinToString("\t"))
        table.rows.forEach { appendLine(it.joinToString("\t")) }
    }.trim()

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    private fun isDelimiter(line: String): Boolean =
        line.contains('-') && line.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
}
