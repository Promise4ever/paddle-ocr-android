package com.example.paddleocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * 回归测试：ONNX Runtime 输出解析必须兼容任意维度嵌套数组，
 * 曾因 det 模型返回 float[][][][] 直接强转 FloatArray 而崩溃。
 */
class OnnxOutputFlattenerTest {

    @Test
    fun `一维 float 数组原样返回`() {
        val input = floatArrayOf(1f, 2f, 3f)
        assertArrayEquals(input, OnnxOutputFlattener.flatten(input), 0f)
    }

    @Test
    fun `二维数组按行主序展平`() {
        val input: Array<FloatArray> = arrayOf(
            floatArrayOf(1f, 2f),
            floatArrayOf(3f, 4f, 5f)
        )
        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f),
            OnnxOutputFlattener.flatten(input),
            0f
        )
    }

    @Test
    fun `四维数组可展平（历史崩溃场景）`() {
        // 对应 det 模型实际返回的 float[][][][]（shape 1,1,H,W）
        val input: Array<Array<Array<FloatArray>>> = arrayOf(
            arrayOf(
                arrayOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)),
                arrayOf(floatArrayOf(5f, 6f), floatArrayOf(7f, 8f))
            )
        )
        assertArrayEquals(
            FloatArray(8) { (it + 1).toFloat() },
            OnnxOutputFlattener.flatten(input),
            0f
        )
    }

    @Test
    fun `混合形状与空数组均可展平`() {
        val input: Array<Array<FloatArray>> = arrayOf(
            arrayOf(floatArrayOf()),
            arrayOf(floatArrayOf(9f)),
            arrayOf(floatArrayOf(10f, 11f))
        )
        assertArrayEquals(
            floatArrayOf(9f, 10f, 11f),
            OnnxOutputFlattener.flatten(input),
            0f
        )
    }

    @Test
    fun `null 元素跳过不崩溃`() {
        val input: Array<FloatArray?> = arrayOf(floatArrayOf(1f), null, floatArrayOf(2f))
        assertArrayEquals(
            floatArrayOf(1f, 2f),
            OnnxOutputFlattener.flatten(input),
            0f
        )
    }

    @Test
    fun `非数组类型抛出 IOException`() {
        assertThrows(IOException::class.java) {
            OnnxOutputFlattener.flatten(IntArray(3))
        }
    }
}
