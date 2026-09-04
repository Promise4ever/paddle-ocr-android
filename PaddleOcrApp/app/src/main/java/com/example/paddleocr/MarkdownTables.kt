package com.example.paddleocr

/**
 * 从 VL 模型返回的 Markdown / HTML 中提取表格。
 *
 * 解析结果用于结果页的“表格视图”：每个表格单独一张卡片展示，
 * 并尽量还原 PaddleOCR 官方识别结果页中表格的阅读体验。
 */
data class MarkdownTable(
    val title: String?,
    val headers: List<String>,
    val rows: List<List<String>>
)

object MarkdownTables {

    /**
     * 解析 Markdown 中的表格。
     * 优先识别 GFM 管道表格；若没有管道表格，再尝试解析 HTML <table>。
     */
    fun parse(markdown: String?): List<MarkdownTable> {
        if (markdown.isNullOrBlank()) return emptyList()
        val pipeTables = parsePipeTables(markdown)
        val htmlTables = parseHtmlTables(stripCodeBlocks(markdown))
        return pipeTables + htmlTables
    }

    private fun parsePipeTables(markdown: String): List<MarkdownTable> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val tables = mutableListOf<MarkdownTable>()
        var i = 0
        var inCode = false

        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.startsWith("```")) {
                inCode = !inCode
                i++
                continue
            }
            if (inCode) {
                i++
                continue
            }

            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (looksLikeTableLine(line) && isDelimiter(next)) {
                val title = findTitle(lines, i)
                val header = parsePipeRow(line)
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size) {
                    val row = lines[j].trim()
                    if (row.isEmpty() || isDelimiter(row) || !looksLikeTableLine(row)) break
                    rows.add(parsePipeRow(row))
                    j++
                }
                tables.add(MarkdownTable(title, header, rows))
                i = j
            } else {
                i++
            }
        }
        return tables
    }

    private fun stripCodeBlocks(markdown: String): String {
        val sb = StringBuilder()
        var inCode = false
        markdown.lineSequence().forEach { line ->
            if (line.trim().startsWith("```")) {
                inCode = !inCode
            } else if (!inCode) {
                sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }

    private fun parseHtmlTables(markdown: String): List<MarkdownTable> {
        val tables = mutableListOf<MarkdownTable>()
        val tableRegex = Regex("<table[^>]*>(.*?)</table>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val rowRegex = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<t[hd][^>]*>(.*?)</t[hd]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        tableRegex.findAll(markdown).forEach { tableMatch ->
            val html = tableMatch.groupValues[1]
            val rows = mutableListOf<List<String>>()
            rowRegex.findAll(html).forEach { rowMatch ->
                val cells = mutableListOf<String>()
                cellRegex.findAll(rowMatch.groupValues[1]).forEach { cellMatch ->
                    val text = cellMatch.groupValues[1]
                    cells.add(stripHtml(text).trim())
                }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            if (rows.isNotEmpty()) {
                tables.add(MarkdownTable(null, rows.first(), rows.drop(1)))
            }
        }
        return tables
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun looksLikeTableLine(line: String): Boolean =
        line.isNotBlank() && line.contains('|') &&
            !line.startsWith(">") && !line.startsWith("-") && !line.startsWith("*") &&
            !line.startsWith("```")

    private fun isDelimiter(line: String): Boolean {
        if (line.isBlank() || !line.contains('-') || !line.contains('|')) return false
        return line.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
    }

    private fun findTitle(lines: List<String>, tableStart: Int): String? {
        var i = tableStart - 1
        while (i >= 0 && lines[i].isBlank()) i--
        if (i < 0) return null
        val prev = lines[i].trim()
        if (prev.isBlank() || prev.contains('|') || isDelimiter(prev)) return null
        if (prev.startsWith("```") || prev.startsWith(">")) return null
        if (prev.length > 120) return null
        return prev.trimStart('#').trim().ifBlank { null }
    }

    private fun parsePipeRow(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)

        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\\' && i + 1 < s.length && s[i + 1] == '|' -> {
                    current.append('|')
                    i += 2
                }
                ch == '|' -> {
                    cells.add(current.toString().trim())
                    current.setLength(0)
                    i++
                }
                else -> {
                    current.append(ch)
                    i++
                }
            }
        }
        cells.add(current.toString().trim())
        return cells
    }
}
