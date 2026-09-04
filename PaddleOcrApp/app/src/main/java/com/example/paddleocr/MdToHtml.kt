package com.example.paddleocr

/**
 * Markdown 子集 → HTML（供 WebView 展示 VL 模型的结构化结果）。
 * 支持：标题、段落、列表（无序/有序）、表格、代码块、引用、粗体/斜体/行内代码/链接。
 * 其余内容按纯文本输出，绝不丢失原文。
 */
object MdToHtml {

    fun convert(md: String, dark: Boolean): String {
        val bg = if (dark) "#121212" else "#FFFFFF"
        val fg = if (dark) "#E4E4E4" else "#1A1A1A"
        val muted = if (dark) "#9E9E9E" else "#6A6A6A"
        val border = if (dark) "#333333" else "#DDDDDD"
        val accent = "#1565C0"
        val body = render(md)
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              body { background:$bg; color:$fg; font-family:-apple-system,'Noto Sans CJK SC','Microsoft YaHei',sans-serif;
                     font-size:16px; line-height:1.7; margin:0; padding:16px; word-break:break-word; }
              h1,h2,h3,h4,h5,h6 { color:$accent; margin:1.2em 0 0.5em; line-height:1.3; }
              h1 { font-size:1.5em; } h2 { font-size:1.3em; } h3 { font-size:1.15em; }
              table { border-collapse:collapse; margin:0.8em 0; width:100%; }
              th,td { border:1px solid $border; padding:6px 10px; font-size:0.92em; }
              th { background:${if (dark) "#1E1E1E" else "#F2F5F9"}; }
              pre { background:${if (dark) "#1E1E1E" else "#F5F6F8"}; padding:12px; border-radius:8px;
                    overflow-x:auto; font-size:0.88em; }
              code { background:${if (dark) "#2A2A2A" else "#F0F2F5"}; padding:1px 5px; border-radius:4px;
                     font-size:0.9em; font-family:Consolas,Menlo,monospace; }
              pre code { background:none; padding:0; }
              blockquote { border-left:4px solid $accent; margin:0.8em 0; padding:4px 12px;
                           color:$muted; background:${if (dark) "#1A1A1A" else "#F8FAFC"}; }
              ul,ol { padding-left:1.5em; }
              a { color:$accent; }
              hr { border:none; border-top:1px solid $border; margin:1.2em 0; }
            </style></head><body>
            $body
            </body></html>
        """.trimIndent()
    }

    private fun render(md: String): String {
        val sb = StringBuilder()
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0
        var inCode = false
        val codeBuf = StringBuilder()
        val tableBuf = mutableListOf<String>()

        fun flushTable() {
            if (tableBuf.isEmpty()) return
            val rows = tableBuf.map { row ->
                row.trim().trim('|').split('|').map { cell ->
                    inline(cell.trim())
                }
            }
            val header = rows.firstOrNull()
            val data = rows.drop(1)
            if (header != null) {
                sb.append("<table><thead><tr>")
                header.forEach { sb.append("<th>").append(it).append("</th>") }
                sb.append("</tr></thead><tbody>")
                data.forEach { cells ->
                    sb.append("<tr>")
                    // 补齐列数
                    repeat(header.size) { idx ->
                        sb.append("<td>").append(cells.getOrElse(idx) { "" }).append("</td>")
                    }
                    sb.append("</tr>")
                }
                sb.append("</tbody></table>")
            }
            tableBuf.clear()
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()

            if (line.startsWith("```")) {
                if (inCode) {
                    sb.append("<pre><code>").append(esc(codeBuf.toString())).append("</code></pre>")
                    codeBuf.clear()
                    inCode = false
                } else {
                    flushTable()
                    inCode = true
                }
                i++
                continue
            }
            if (inCode) {
                codeBuf.append(raw).append('\n')
                i++
                continue
            }
            if (line.isEmpty()) {
                flushTable()
                i++
                continue
            }
            // 表格：当前行含 | 且下一行是分隔行（---），支持有无首尾竖线
            if (line.contains('|') && i + 1 < lines.size &&
                isDelimiter(lines[i + 1].trim())
            ) {
                tableBuf.clear()
                tableBuf.add(line)
                tableBuf.add(lines[i + 1].trim())
                var j = i + 2
                while (j < lines.size && isTableRow(lines[j].trim())) {
                    tableBuf.add(lines[j].trim())
                    j++
                }
                flushTable()
                i = j
                continue
            }
            // 引用
            if (line.startsWith(">")) {
                sb.append("<blockquote>").append(inline(line.removePrefix(">").trim())).append("</blockquote>")
                i++
                continue
            }
            // 标题
            val h = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (h != null) {
                val level = h.groupValues[1].length
                sb.append("<h$level>").append(inline(h.groupValues[2])).append("</h$level>")
                i++
                continue
            }
            // 无序列表
            val ul = Regex("^[-*+]\\s+(.*)$").find(line)
            if (ul != null) {
                sb.append("<ul><li>").append(inline(ul.groupValues[1])).append("</li></ul>")
                i++
                continue
            }
            // 有序列表
            val ol = Regex("^\\d+\\.\\s+(.*)$").find(line)
            if (ol != null) {
                sb.append("<ol><li>").append(inline(ol.groupValues[1])).append("</li></ol>")
                i++
                continue
            }
            // 分隔线
            if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(line)) {
                sb.append("<hr>")
                i++
                continue
            }
            sb.append("<p>").append(inline(line)).append("</p>")
            i++
        }
        if (inCode) {
            sb.append("<pre><code>").append(esc(codeBuf.toString())).append("</code></pre>")
        }
        flushTable()
        return sb.toString()
    }

    private fun isDelimiter(line: String): Boolean {
        if (line.isBlank() || !line.contains('-') || !line.contains('|')) return false
        return line.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
    }

    private fun isTableRow(line: String): Boolean =
        line.contains('|') && !isDelimiter(line)

    /** 行内语法：粗体/斜体/行内代码/链接 */
    private fun inline(s: String): String {
        var out = esc(sanitize(s))
        // 行内代码（先保护，避免被后续规则破坏）
        val codeSpans = mutableListOf<String>()
        out = Regex("`([^`]+)`").replace(out) {
            // out 已经整体 esc 过一次，这里直接存原文，避免 &lt; 被二次转义成 &amp;lt;
            codeSpans.add(it.groupValues[1])
            "\u0001${codeSpans.size - 1}\u0001"
        }
        // [text](url)
        out = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)").replace(out) {
            "<a href=\"${esc(it.groupValues[2])}\">${it.groupValues[1]}</a>"
        }
        // **bold**
        out = Regex("\\*\\*([^*]+)\\*\\*").replace(out, "<strong>$1</strong>")
        // *italic*
        out = Regex("(?<!\\*)\\*([^*\\s][^*]*)\\*(?!\\*)").replace(out, "<em>$1</em>")
        // 还原行内代码
        out = Regex("\u0001(\\d+)\u0001").replace(out) {
            "<code>${codeSpans.getOrElse(it.groupValues[1].toInt()) { "" }}</code>"
        }
        return out
    }

    /**
     * 净化 VL 模型 markdown 中内嵌的 HTML（如聊天截图里的图片占位
     * &lt;div&gt;&lt;img …/&gt;&lt;/div&gt;）：
     * 图片与标签不属于识别文字，直接移除，保证结果只有真实文字内容。
     */
    fun sanitize(s: String): String {
        var out = Regex("<img[^>]*?>", RegexOption.IGNORE_CASE).replace(s, "")
        out = Regex("<[^>]+>").replace(out, "")
        return out
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
