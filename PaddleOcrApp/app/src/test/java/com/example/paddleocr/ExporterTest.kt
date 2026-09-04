package com.example.paddleocr

import com.example.paddleocr.data.OcrLine
import com.example.paddleocr.data.OcrResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTest {

    @Test
    fun `markdown导出会将HTML表格转换为管道表格`() {
        val html = "<table><tr><th>名称</th><th>数量</th></tr>" +
            "<tr><td>苹果</td><td>3</td></tr></table>"
        val result = OcrResult(
            lines = listOf(OcrLine(html, 0.0)),
            markdown = null
        )

        val md = Exporter.markdownText(result)

        assertTrue(md.contains("| 名称 | 数量 |"))
        assertTrue(md.contains("| 苹果 | 3 |"))
        assertFalse(md.contains("<table"))
    }

    @Test
    fun `TXT导出会将HTML表格输出为可读纯文本`() {
        val html = "<table><tr><th>名称</th><th>数量</th></tr>" +
            "<tr><td>苹果</td><td>3</td></tr></table>"
        val result = OcrResult(
            lines = listOf(OcrLine(html, 0.0)),
            markdown = null
        )

        val txt = Exporter.plainText(result)

        assertTrue(txt.contains("名称"))
        assertTrue(txt.contains("苹果"))
        assertFalse(txt.contains("<table"))
        assertFalse(txt.contains("| 名称"))
    }

    @Test
    fun `Markdown导出保持文字和表格顺序`() {
        val source = "前文<table><tr><th>名称</th></tr><tr><td>苹果</td></tr></table>后文"
        val result = OcrResult(lines = listOf(OcrLine(source, 0.0)), markdown = source)

        val md = Exporter.markdownText(result)

        assertTrue(md.indexOf("前文") < md.indexOf("| 名称 |"))
        assertTrue(md.indexOf("| 名称 |") < md.indexOf("后文"))
    }
}
