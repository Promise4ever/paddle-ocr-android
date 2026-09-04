package com.example.paddleocr

/** 识别结果中的有序内容块，用于格式化显示和导出。 */
sealed interface FormattedBlock {
    data class Text(val source: String) : FormattedBlock
    data class Table(val table: MarkdownTable) : FormattedBlock
}

object FormattedContent {

    private val htmlTableRegex = Regex(
        "<table[^>]*>.*?</table>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val codeBlockRegex = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)

    /** 同时识别 HTML 表格和 Markdown 管道表格，并保留它们与普通文字的原始顺序。 */
    fun parse(source: String?): List<FormattedBlock> {
        if (source.isNullOrBlank()) return emptyList()
        val blocks = mutableListOf<FormattedBlock>()
        val masked = source.toCharArray()
        codeBlockRegex.findAll(source).forEach { match ->
            for (index in match.range) {
                if (masked[index] != '\n' && masked[index] != '\r') masked[index] = ' '
            }
        }

        var cursor = 0
        htmlTableRegex.findAll(String(masked)).forEach { match ->
            appendPipeBlocks(source.substring(cursor, match.range.first), blocks)
            val tableSource = source.substring(match.range.first, match.range.last + 1)
            val table = MarkdownTables.parse(tableSource).firstOrNull()
            if (table != null) blocks.add(FormattedBlock.Table(table))
            else appendText(tableSource, blocks)
            cursor = match.range.last + 1
        }
        appendPipeBlocks(source.substring(cursor), blocks)
        return blocks
    }

    private fun appendPipeBlocks(source: String, blocks: MutableList<FormattedBlock>) {
        if (source.isBlank()) return
        val lines = source.replace("\r\n", "\n").split('\n')
        val text = mutableListOf<String>()
        var inCode = false
        var i = 0

        fun flushText() {
            appendText(text.joinToString("\n"), blocks)
            text.clear()
        }

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("```")) {
                inCode = !inCode
                text.add(lines[i])
                i++
                continue
            }
            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (!inCode && looksLikeTableLine(line) && isDelimiter(next)) {
                flushText()
                val tableLines = mutableListOf(lines[i], lines[i + 1])
                var j = i + 2
                while (j < lines.size) {
                    val row = lines[j].trim()
                    if (row.isEmpty() || !looksLikeTableLine(row) || isDelimiter(row)) break
                    tableLines.add(lines[j])
                    j++
                }
                val tableSource = tableLines.joinToString("\n")
                val table = MarkdownTables.parse(tableSource).firstOrNull()
                if (table != null) blocks.add(FormattedBlock.Table(table))
                else appendText(tableSource, blocks)
                i = j
            } else {
                text.add(lines[i])
                i++
            }
        }
        flushText()
    }

    private fun appendText(source: String, blocks: MutableList<FormattedBlock>) {
        val value = source.trim()
        if (value.isBlank()) return
        val previous = blocks.lastOrNull()
        if (previous is FormattedBlock.Text) {
            blocks[blocks.lastIndex] = FormattedBlock.Text(previous.source + "\n\n" + value)
        } else {
            blocks.add(FormattedBlock.Text(value))
        }
    }

    private fun looksLikeTableLine(line: String): Boolean =
        line.isNotBlank() && line.contains('|') &&
            !line.startsWith(">") && !line.startsWith("-") &&
            !line.startsWith("*") && !line.startsWith("```")

    private fun isDelimiter(line: String): Boolean {
        if (line.isBlank() || !line.contains('-') || !line.contains('|')) return false
        return line.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
    }
}
