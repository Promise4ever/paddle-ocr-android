package com.example.paddleocr

import java.io.IOException

/**
 * 将 ONNX Runtime 返回的任意维度嵌套数组统一展平为行主序 FloatArray。
 *
 * 不同模型 / 不同导出方式会返回 FloatArray、float[][]、float[][][][] 等不同形状，
 * 这里统一处理，避免 ClassCastException（det 模型曾真实返回 float[][][][] 导致崩溃）。
 */
internal object OnnxOutputFlattener {

    fun flatten(value: Any): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val parts = value.map { it?.let(::flatten) ?: FloatArray(0) }
            val total = parts.sumOf { it.size }
            val out = FloatArray(total)
            var idx = 0
            for (p in parts) {
                System.arraycopy(p, 0, out, idx, p.size)
                idx += p.size
            }
            out
        }
        else -> throw IOException("无法解析模型输出类型：${value.javaClass.simpleName}")
    }
}
