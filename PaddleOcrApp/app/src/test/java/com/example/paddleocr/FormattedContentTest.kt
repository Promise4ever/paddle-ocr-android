package com.example.paddleocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattedContentTest {

    @Test
    fun `HTML表格与文字保持原始顺序`() {
        val source = """
            表格前说明
            <table><tr><th>名称</th><th>数量</th></tr><tr><td>苹果</td><td>3</td></tr></table>
            表格后说明
        """.trimIndent()

        val blocks = FormattedContent.parse(source)

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is FormattedBlock.Text)
        assertTrue(blocks[1] is FormattedBlock.Table)
        assertTrue(blocks[2] is FormattedBlock.Text)
        assertEquals("表格前说明", (blocks[0] as FormattedBlock.Text).source)
        assertEquals("表格后说明", (blocks[2] as FormattedBlock.Text).source)
    }

    @Test
    fun `Markdown表格与文字保持原始顺序`() {
        val source = """
            开头

            | 名称 | 数量 |
            | --- | --- |
            | 苹果 | 3 |

            结尾
        """.trimIndent()

        val blocks = FormattedContent.parse(source)

        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is FormattedBlock.Table)
        assertEquals(listOf("名称", "数量"), (blocks[1] as FormattedBlock.Table).table.headers)
    }
}
