package com.example.paddleocr.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 单行识别结果。
 * polygon 为相对输入图片的归一化四点坐标（8 个 float：[x0,y0,x1,y1,x2,y2,x3,y3]，0..1），
 * 来自 PaddleX/PP-OCR 的 rec_polys，用于在结果页叠加识别框；云端 VL 模型无此信息时为 null。
 */
data class OcrLine(
    val text: String,
    val confidence: Double,
    val polygon: List<Float>? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("confidence", confidence)
        .apply {
            polygon?.let { poly ->
                put("polygon", JSONArray().apply { poly.forEach(::put) })
            }
        }

    companion object {
        fun fromJson(o: JSONObject): OcrLine {
            val poly = o.optJSONArray("polygon")?.let { arr ->
                (0 until arr.length()).map { arr.optDouble(it).toFloat() }
            }
            return OcrLine(o.optString("text"), o.optDouble("confidence", 0.0), poly)
        }
    }
}

/**
 * 一次完整的 OCR 结果（可入库/展示）。
 * @param lines 纯文本行（供复制、搜索、行统计）
 * @param markdown VL 模型返回的结构化 Markdown 原文（可为 null）
 * @param modelName 实际使用的模型（云端自动切换后可能与设置不同）
 * @param sourceImagePath 识别时输入图片的本地副本（filesDir），用于识别框叠加
 * @param imageWidth/imageHeight 输入图片尺寸，polygon 归一化坐标以此为参照
 * @param isCloud 是否云端计费任务（影响配额统计口径）
 */
data class OcrResult(
    val lines: List<OcrLine>,
    val markdown: String? = null,
    val modelName: String? = null,
    val sourceImagePath: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val isCloud: Boolean = false
) {
    val fullText: String get() = lines.joinToString("\n") { it.text }

    fun toJson(): JSONObject = JSONObject()
        .put("lines", JSONArray().apply { lines.forEach { put(it.toJson()) } })
        .put("markdown", markdown ?: "")
        .put("modelName", modelName ?: "")
        .put("sourceImagePath", sourceImagePath ?: "")
        .put("imageWidth", imageWidth)
        .put("imageHeight", imageHeight)
        .put("isCloud", isCloud)

    companion object {
        fun fromJson(o: JSONObject): OcrResult {
            val arr = o.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until arr.length()).mapNotNull {
                arr.optJSONObject(it)?.let(JSONObject::toString)?.let { s ->
                    runCatching { OcrLine.fromJson(JSONObject(s)) }.getOrNull()
                }
            }
            return OcrResult(
                lines = lines,
                markdown = o.optString("markdown").ifBlank { null },
                modelName = o.optString("modelName").ifBlank { null },
                sourceImagePath = o.optString("sourceImagePath").ifBlank { null },
                imageWidth = o.optInt("imageWidth", 0),
                imageHeight = o.optInt("imageHeight", 0),
                isCloud = o.optBoolean("isCloud", false)
            )
        }
    }
}
