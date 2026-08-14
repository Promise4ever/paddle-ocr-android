package com.example.paddleocr

import com.example.paddleocr.data.OcrResult
import com.example.paddleocr.data.ResultRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 结果导出：TXT / Markdown。
 * 生成到 cacheDir，由调用方通过 SAF 保存或分享。
 */
object Exporter {

    fun buildTxt(result: OcrResult): File {
        val f = newFile("txt")
        // 仅 Markdown 结果时回退到原文，避免导出空文件
        f.writeText(result.fullText.ifBlank { result.markdown.orEmpty() }, Charsets.UTF_8)
        return f
    }

    fun buildMarkdown(result: OcrResult): File {
        val f = newFile("md")
        // 导出前净化内嵌 HTML，保证文件干净可读
        val md = MdToHtml.sanitize(
            result.markdown?.takeIf { it.isNotBlank() } ?: result.fullText
        )
        f.writeText(md, Charsets.UTF_8)
        return f
    }

    private fun newFile(ext: String): File {
        val name = "OCR_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.$ext"
        return ResultRepository.cacheFile(name)
    }
}
