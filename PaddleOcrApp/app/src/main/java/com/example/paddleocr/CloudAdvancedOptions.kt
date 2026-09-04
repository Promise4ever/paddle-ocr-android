package com.example.paddleocr

import org.json.JSONArray
import org.json.JSONObject

enum class CloudModelFamily {
    VL,
    OCR,
    STRUCTURE,
    UNSUPPORTED
}

/**
 * AI Studio optionalPayload 的模型分组与编辑页初始值。
 *
 * 未保存高级设置时请求仍发送空对象，由云端决定默认参数；这些初始值只用于首次打开编辑页。
 */
object CloudAdvancedOptions {

    val auxiliaryLabels = linkedMapOf(
        "header" to "页眉",
        "header_image" to "页眉图片",
        "footer" to "页脚",
        "footer_image" to "页脚图片",
        "number" to "页码",
        "footnote" to "脚注",
        "aside_text" to "旁注文本"
    )

    fun familyOf(model: String): CloudModelFamily = when {
        model.startsWith("PaddleOCR-VL") -> CloudModelFamily.VL
        model == "PP-OCRv6" || model.startsWith("PP-OCRv5") -> CloudModelFamily.OCR
        model == "PP-StructureV3" -> CloudModelFamily.STRUCTURE
        else -> CloudModelFamily.UNSUPPORTED
    }

    fun defaultPayload(model: String): JSONObject = when (familyOf(model)) {
        CloudModelFamily.VL -> JSONObject()
            .put("useDocOrientationClassify", false)
            .put("useDocUnwarping", false)
            .put("useLayoutDetection", true)
            .put("useChartRecognition", false)
            .put("useSealRecognition", true)
            .put("useOcrForImageBlock", false)
            .put("mergeTables", true)
            .put("relevelTitles", true)
            .put("layoutShapeMode", "auto")
            .put("promptLabel", "ocr")
            .put("repetitionPenalty", 1.0)
            .put("temperature", 0.0)
            .put("markdownIgnoreLabels", JSONArray(auxiliaryLabels.keys))

        CloudModelFamily.OCR -> JSONObject()
            .put("useDocOrientationClassify", true)
            .put("useDocUnwarping", true)
            .put("useTextlineOrientation", true)
            .put("textDetLimitSideLen", 64)
            .put("textDetLimitType", "min")
            .put("textDetThresh", 0.3)
            .put("textDetBoxThresh", 0.6)
            .put("textDetUnclipRatio", 1.5)
            .put("textRecScoreThresh", 0.0)

        CloudModelFamily.STRUCTURE -> JSONObject()
            .put("useDocOrientationClassify", true)
            .put("useDocUnwarping", true)
            .put("useTextlineOrientation", true)
            .put("useRegionDetection", true)
            .put("useTableRecognition", true)
            .put("useFormulaRecognition", true)
            .put("useChartRecognition", false)
            .put("useSealRecognition", true)
            .put("prettifyMarkdown", true)
            .put("showFormulaNumber", true)
            .put("textDetLimitSideLen", 960)
            .put("textDetLimitType", "max")
            .put("textRecScoreThresh", 0.0)

        CloudModelFamily.UNSUPPORTED -> JSONObject()
    }
}
