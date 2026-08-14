package com.example.paddleocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.paddleocr.data.OcrResult

/**
 * 在识别原图上叠加每个文字检测框（编号 + 彩色边框）。
 * polygon 为归一化坐标，按图片宽高映射回屏幕。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var result: OcrResult? = null

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        color = Color.parseColor("#FF6D00")
        isAntiAlias = true
    }
    private val badgePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF6D00")
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val bgPaint = Paint().apply { color = Color.BLACK }

    private val colors = listOf(
        "#FF6D00", "#00B8D4", "#7CB342", "#E040FB", "#F4511E",
        "#3949AB", "#00897B", "#C0CA33", "#D81B60", "#5E35B1"
    )

    fun setData(bitmap: Bitmap, result: OcrResult) {
        this.bitmap = bitmap
        this.result = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val res = result ?: return
        canvas.drawColor(bgPaint.color)

        // 保持宽高比居中绘制
        val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), null)

        var index = 0
        for (line in res.lines) {
            val poly = line.polygon ?: continue
            if (poly.size < 8) continue
            borderPaint.color = Color.parseColor(colors[index % colors.size])

            val path = android.graphics.Path()
            for (i in 0 until 4) {
                val px = left + poly[i * 2] * dw
                val py = top + poly[i * 2 + 1] * dh
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            canvas.drawPath(path, borderPaint)

            // 编号徽标
            val label = (index + 1).toString()
            val bx = left + poly[0] * dw
            val by = top + poly[1] * dh
            val tw = textPaint.measureText(label) + 8f * resources.displayMetrics.density
            val th = textPaint.textSize + 6f * resources.displayMetrics.density
            canvas.drawRoundRect(
                RectF(bx, by - th, bx + tw, by),
                4f * resources.displayMetrics.density,
                4f * resources.displayMetrics.density,
                badgePaint
            )
            canvas.drawText(label, bx + 4f * resources.displayMetrics.density, by - 3f * resources.displayMetrics.density, textPaint)
            index++
        }
    }
}
