package com.example.paddleocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTablesTest {

    @Test
    fun `解析带标题的标准表格`() {
        val md = """
            # 商品进销存

            | 商品编码 | 商品名称 | 数量 |
            | --- | --- | --- |
            | 1001 | 苹果 | 3 |
            | 1002 | 香蕉 | 5 |
        """.trimIndent()

        val tables = MarkdownTables.parse(md)
        assertEquals(1, tables.size)
        val table = tables.first()
        assertEquals("商品进销存", table.title)
        assertEquals(listOf("商品编码", "商品名称", "数量"), table.headers)
        assertEquals(listOf(listOf("1001", "苹果", "3"), listOf("1002", "香蕉", "5")), table.rows)
    }

    @Test
    fun `支持无首尾竖线的表格`() {
        val md = """
            名称 | 价格
            --- | ---
            可乐 | 3.5
            雪碧 | 4.0
        """.trimIndent()

        val tables = MarkdownTables.parse(md)
        assertEquals(1, tables.size)
        assertEquals(listOf("名称", "价格"), tables.first().headers)
        assertEquals(listOf(listOf("可乐", "3.5"), listOf("雪碧", "4.0")), tables.first().rows)
    }

    @Test
    fun `单元格内转义竖线不拆列`() {
        val md = """
            | A | B |
            | --- | --- |
            | 1 | a\|b |
        """.trimIndent()

        val tables = MarkdownTables.parse(md)
        assertEquals(1, tables.size)
        assertEquals(listOf("1", "a|b"), tables.first().rows.first())
    }

    @Test
    fun `代码块中的竖线不识别为表格`() {
        val md = """
            ```
            | 假表格 | 假数据 |
            | --- | --- |
            ```
        """.trimIndent()

        assertTrue(MarkdownTables.parse(md).isEmpty())
    }

    @Test
    fun `解析HTML表格`() {
        val md = """
            <table>
                <tr><th>名称</th><th>数量</th></tr>
                <tr><td>苹果</td><td>3</td></tr>
            </table>
        """.trimIndent()

        val tables = MarkdownTables.parse(md)
        assertEquals(1, tables.size)
        assertEquals(listOf("名称", "数量"), tables.first().headers)
        assertEquals(listOf(listOf("苹果", "3")), tables.first().rows)
    }

    @Test
    fun `代码块中的HTML表格不识别`() {
        val md = """
            ```
            <table><tr><th>A</th></tr><tr><td>1</td></tr></table>
            ```
        """.trimIndent()

        assertTrue(MarkdownTables.parse(md).isEmpty())
    }

}
