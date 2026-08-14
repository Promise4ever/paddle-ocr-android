package com.example.paddleocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 图片区域选择控件：显示图片，支持拖拽框选、整体移动和八方向手柄微调，
 * 拖拽时右上角显示放大镜便于精确定位。
 */
class RegionSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "RSV"
    }

    private val density: Float = resources.displayMetrics.density

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            selection = RectF(0.2f, 0.2f, 0.8f, 0.8f)
            computeImageRect()
            post { updateGestureExclusion() }
            invalidate()
        }

    var onSelectionChanged: ((RectF) -> Unit)? = null

    private val imageMatrix = Matrix()
    private val imageRect = RectF()

    /** 选择区域，坐标归一化到图片（0..1） */
    private var selection = RectF(0.2f, 0.2f, 0.8f, 0.8f)

    private enum class Mode { NONE, DRAW, MOVE, RESIZE }

    private var mode = Mode.NONE
    private var activeHandle = -1
    private var downX = 0f
    private var downY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var downRect = RectF()

    private val dimPaint = Paint().apply {
        color = 0x99000000.toInt()
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.WHITE
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        isAntiAlias = true
    }
    private val handleStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#1976D2")
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x66FFFFFF.toInt()
        isAntiAlias = true
    }
    private val magnifierRingPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.parseColor("#1976D2")
        isAntiAlias = true
    }
    private val crosshairPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.RED
        isAntiAlias = true
    }

    private val handleRadius = 16f * density
    private val touchSlop = 30f * density

    fun resetSelection() {
        selection.set(0f, 0f, 1f, 1f)
        invalidate()
        onSelectionChanged?.invoke(RectF(selection))
    }

    fun getNormalizedSelection(): RectF = RectF(selection)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeImageRect()
        updateGestureExclusion()
    }

    private fun computeImageRect() {
        val bmp = bitmap ?: return
        // 加大内边距，让选区手柄远离屏幕边缘，避免与系统返回手势冲突
        val pad = 48f * density
        val availW = width - pad * 2
        val availH = height - pad * 2
        if (availW <= 0 || availH <= 0) return
        val scale = min(availW / bmp.width.toFloat(), availH / bmp.height.toFloat())
        val w = bmp.width.toFloat() * scale
        val h = bmp.height.toFloat() * scale
        imageRect.set(
            (width.toFloat() - w) / 2f,
            (height.toFloat() - h) / 2f,
            (width.toFloat() + w) / 2f,
            (height.toFloat() + h) / 2f
        )
        imageMatrix.reset()
        imageMatrix.setRectToRect(
            RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
            imageRect,
            Matrix.ScaleToFit.FILL
        )
        Log.i(TAG, "imageRect=$imageRect view=${width}x$height bmp=${bmp.width}x${bmp.height}")
    }

    /**
     * 把控件左右边缘的区域声明为系统手势排除区（Android 10+），
     * 这样在边缘手柄上开始拖拽时不会被系统返回手势抢走。
     */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && width > 0 && height > 0) {
            val strip = (64f * density).toInt()
            ViewCompat.setSystemGestureExclusionRects(
                this,
                listOf(
                    Rect(0, 0, strip, height),
                    Rect(width - strip, 0, width, height)
                )
            )
        }
    }

    private fun normX(x: Float) = (x - imageRect.left) / imageRect.width()
    private fun normY(y: Float) = (y - imageRect.top) / imageRect.height()
    private fun screenX(nx: Float) = imageRect.left + nx * imageRect.width()
    private fun screenY(ny: Float) = imageRect.top + ny * imageRect.height()

    private fun selectionScreen(): RectF = RectF(
        screenX(selection.left), screenY(selection.top),
        screenX(selection.right), screenY(selection.bottom)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, imageMatrix, null)

        val sel = selectionScreen()
        if (sel.width() < 1f || sel.height() < 1f) return

        // 选区外的暗色遮罩
        canvas.save()
        val dim = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), Path.Direction.CW)
            addRect(sel, Path.Direction.CW)
        }
        canvas.drawPath(dim, dimPaint)
        canvas.restore()

        // 三分线
        for (i in 1..2) {
            val gx = sel.width() / 3f * i
            val gy = sel.height() / 3f * i
            canvas.drawLine(sel.left + gx, sel.top, sel.left + gx, sel.bottom, gridPaint)
            canvas.drawLine(sel.left, sel.top + gy, sel.right, sel.top + gy, gridPaint)
        }

        canvas.drawRect(sel, borderPaint)

        if (mode == Mode.NONE) {
            drawHandles(canvas, sel)
        }
        if (mode != Mode.NONE) {
            drawMagnifier(canvas, bmp)
        }
    }

    private fun drawHandles(canvas: Canvas, sel: RectF) {
        handlePositions(sel).forEach { (hx, hy) ->
            canvas.drawCircle(hx, hy, handleRadius, handlePaint)
            canvas.drawCircle(hx, hy, handleRadius, handleStrokePaint)
        }
    }

    private fun handlePositions(sel: RectF): List<Pair<Float, Float>> = listOf(
        sel.left to sel.top,
        sel.centerX() to sel.top,
        sel.right to sel.top,
        sel.left to sel.centerY(),
        sel.right to sel.centerY(),
        sel.left to sel.bottom,
        sel.centerX() to sel.bottom,
        sel.right to sel.bottom
    )

    private fun hitHandle(x: Float, y: Float): Int {
        handlePositions(selectionScreen()).forEachIndexed { index, (hx, hy) ->
            if (abs(x - hx) <= touchSlop && abs(y - hy) <= touchSlop) return index
        }
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                activeHandle = hitHandle(x, y)
                when {
                    activeHandle >= 0 -> {
                        mode = Mode.RESIZE
                        downRect.set(selection)
                    }
                    selectionScreen().contains(x, y) -> {
                        mode = Mode.MOVE
                        activeHandle = -1
                        downRect.set(selection)
                    }
                    imageRect.contains(x, y) -> {
                        mode = Mode.DRAW
                        activeHandle = -1
                        val nx = normX(x).coerceIn(0f, 1f)
                        val ny = normY(y).coerceIn(0f, 1f)
                        selection.set(nx, ny, nx, ny)
                    }
                    else -> return false
                }
                downX = x
                downY = y
                touchX = x
                touchY = y
                Log.i(TAG, "DOWN x=$x y=$y mode=$mode handle=$activeHandle")
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return true
                // 基于按下时的锚点计算绝对位移：即使系统合并/丢弃了中间的
                // MOVE 事件，最终选区位置也能与手指当前位置严格一致。
                val dx = (event.x - downX) / imageRect.width()
                val dy = (event.y - downY) / imageRect.height()
                when (mode) {
                    Mode.MOVE -> {
                        val w = downRect.width()
                        val h = downRect.height()
                        val left = (downRect.left + dx).coerceIn(0f, 1f - w)
                        val top = (downRect.top + dy).coerceIn(0f, 1f - h)
                        selection.set(left, top, left + w, top + h)
                    }

                    Mode.DRAW -> {
                        val nx = normX(event.x).coerceIn(0f, 1f)
                        val ny = normY(event.y).coerceIn(0f, 1f)
                        val l = min(downRect.left, nx)
                        val t = min(downRect.top, ny)
                        val r = max(downRect.left, nx)
                        val b = max(downRect.top, ny)
                        selection.set(l, t, r, b)
                    }

                    Mode.RESIZE -> {
                        var l = downRect.left
                        var t = downRect.top
                        var r = downRect.right
                        var b = downRect.bottom
                        when (activeHandle) {
                            0 -> { l = downRect.left + dx; t = downRect.top + dy }
                            1 -> { t = downRect.top + dy }
                            2 -> { r = downRect.right + dx; t = downRect.top + dy }
                            3 -> { l = downRect.left + dx }
                            4 -> { r = downRect.right + dx }
                            5 -> { l = downRect.left + dx; b = downRect.bottom + dy }
                            6 -> { b = downRect.bottom + dy }
                            7 -> { r = downRect.right + dx; b = downRect.bottom + dy }
                        }
                        // 先保证 r/b 不小于最小尺寸，避免 coerceIn 区间为空抛异常
                        val minSize = 0.03f
                        r = r.coerceIn(minSize, 1f)
                        b = b.coerceIn(minSize, 1f)
                        l = l.coerceIn(0f, r - minSize)
                        t = t.coerceIn(0f, b - minSize)
                        r = r.coerceIn(l + minSize, 1f)
                        b = b.coerceIn(t + minSize, 1f)
                        selection.set(l, t, r, b)
                    }

                    else -> {}
                }
                touchX = event.x
                touchY = event.y
                clampSelection()
                Log.i(TAG, "MOVE x=${event.x} y=${event.y} mode=$mode sel=$selection")
                invalidate()
                onSelectionChanged?.invoke(RectF(selection))
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 与 RESIZE 最小尺寸统一，避免“点按”产生的微小区块在后续
                // 拖动手柄时因 coerceIn 空区间崩溃
                if (mode == Mode.DRAW && selection.width() < 0.03f) {
                    selection.set(0f, 0f, 1f, 1f)
                }
                mode = Mode.NONE
                activeHandle = -1
                clampSelection()
                Log.i(TAG, "UP sel=$selection")
                invalidate()
                onSelectionChanged?.invoke(RectF(selection))
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampSelection() {
        selection.left = selection.left.coerceIn(0f, 1f)
        selection.top = selection.top.coerceIn(0f, 1f)
        selection.right = selection.right.coerceIn(0f, 1f)
        selection.bottom = selection.bottom.coerceIn(0f, 1f)
    }

    private fun drawMagnifier(canvas: Canvas, bmp: Bitmap) {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return
        val radius = 88f * density
        val margin = 16f * density
        val cx = imageRect.right - radius - margin
        val cy = imageRect.top + radius + margin

        // 屏幕像素 <-> 图片像素比例
        val pxPerScreen = bmp.width.toFloat() / imageRect.width()
        val srcSize = (2f * radius) / 2.5f * pxPerScreen
        // 放大镜中心 = 手指当前位置（而不是选区中心），拖到哪放大哪
        val cxImg = normX(touchX).coerceIn(0f, 1f) * bmp.width.toFloat()
        val cyImg = normY(touchY).coerceIn(0f, 1f) * bmp.height.toFloat()

        val src = RectF(
            cxImg - srcSize / 2f,
            cyImg - srcSize / 2f,
            cxImg + srcSize / 2f,
            cyImg + srcSize / 2f
        ).apply {
            left = left.coerceIn(0f, bmp.width.toFloat() - 1f)
            top = top.coerceIn(0f, bmp.height.toFloat() - 1f)
            right = right.coerceIn(left + 1f, bmp.width.toFloat())
            bottom = bottom.coerceIn(top + 1f, bmp.height.toFloat())
        }
        val dst = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val srcRect = Rect(
            src.left.toInt(),
            src.top.toInt(),
            src.right.toInt(),
            src.bottom.toInt()
        )

        canvas.save()
        val clip = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bmp, srcRect, dst, null)
        // 中心十字线
        val len = 14f * density
        canvas.drawLine(cx - len, cy, cx + len, cy, crosshairPaint)
        canvas.drawLine(cx, cy - len, cx, cy + len, crosshairPaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, magnifierRingPaint)
    }
}
