package com.example.paddleocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdToHtmlTest {

    @Test
    fun `行内代码中的特殊字符只转义一次`() {
        val html = MdToHtml.convert("`a<b & c`", dark = false)
        assertTrue(html.contains("<code>a&lt;b &amp; c</code>"))
        assertFalse(html.contains("&amp;lt;"))
        assertFalse(html.contains("<code>a<b"))
    }

    @Test
    fun `内嵌 img 标签被清除`() {
        val html = MdToHtml.convert(
            "前文 <img src=\"x.png\" alt=\"Image\"> 后文",
            dark = false
        )
        assertTrue(html.contains("前文  后文"))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun `标题与表格正常渲染`() {
        val md = "# 标题\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        val html = MdToHtml.convert(md, dark = false)
        assertTrue(html.contains("<h1>标题</h1>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<td>1</td>"))
    }

    @Test
    fun `无首尾竖线表格正常渲染`() {
        val md = "A | B\n--- | ---\n1 | 2"
        val html = MdToHtml.convert(md, dark = false)
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<td>1</td>"))
    }
}
