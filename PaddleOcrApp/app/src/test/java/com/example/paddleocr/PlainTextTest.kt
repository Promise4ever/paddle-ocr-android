package com.example.paddleocr

import com.example.paddleocr.data.OcrLine
import com.example.paddleocr.data.OcrResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextTest {

    @Test
    fun `纯文本会解码HTML实体`() {
        val result = OcrResult(
            lines = listOf(OcrLine("&gt;1 &lt;x&gt;", 0.0)),
            markdown = null
        )

        val text = PlainText.convert(result)

        assertTrue(text.contains(">1 <x>"))
        assertFalse(text.contains("&gt;"))
        assertFalse(text.contains("&lt;"))
    }

    @Test
    fun `复制表格内容没有Markdown管道符`() {
        val html = "<table><tr><th>名称</th><th>数量</th></tr>" +
            "<tr><td>&gt;1</td><td>3</td></tr></table>"
        val result = OcrResult(
            lines = listOf(OcrLine(html, 0.0)),
            markdown = null
        )

        val text = PlainText.convert(result)

        assertTrue(text.contains(">1"))
        assertFalse(text.contains("<table"))
        assertFalse(text.contains("| 名称"))
    }

    @Test
    fun `全文本中的表格不会被移动到文末`() {
        val source = "前文<table><tr><th>名称</th></tr><tr><td>苹果</td></tr></table>后文"
        val result = OcrResult(lines = listOf(OcrLine(source, 0.0)), markdown = source)

        val text = PlainText.convert(result)

        assertTrue(text.indexOf("前文") < text.indexOf("名称"))
        assertTrue(text.indexOf("名称") < text.indexOf("后文"))
    }
}
