package com.example.paddleocr

import android.content.Context
import android.graphics.Bitmap
import com.example.paddleocr.data.OcrLine
import com.example.paddleocr.data.OcrResult
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 端侧离线 OCR：内置 PP-OCRv4 mobile 的 ONNX 模型（det + rec）跑在本机。
 *
 * 管线（v2，大幅提升精度）：
 * 1. det：长边 960、32 对齐缩放 → DBNet 推理 → 概率图阈值/膨胀/findContours 得检测框
 * 2. 行合并：按 y 中心分组，x 方向重叠/相邻的框合并为整行
 * 3. 边界修正：在"外扩的搜索区"内用灰度二值化（Otsu）投影求真实文字边界，
 *    解决 det 框漏掉行首尾字符导致的缺字
 * 4. 逐行：按比例裁剪原图 → 固定 resize 320×48（对齐官方预处理分布）→ rec → CTC 解码；
 *    超长行（宽高比>12）等分切分（带 6% 重叠）后逐段识别
 *
 * 线程安全：整个识别流程串行化。
 */
object OfflineOcrEngine {

    private const val DET_MAX_SIDE = 960
    private const val DET_THRESH = 0.3f
    private const val UNCLIP_RATIO = 2.0f
    private const val REC_HEIGHT = 48
    private const val REC_WIDTH = 320
    /** 行宽高比超过该值才切分 */
    private const val SPLIT_RATIO = 12.0f
    /** 行切分后每段的最大宽高比 */
    private const val PIECE_RATIO = 10.0f

    private const val ASSET_DET = "models/ch_PP-OCRv4_det_infer.onnx"
    private const val ASSET_REC = "models/ch_PP-OCRv4_rec_infer.onnx"
    private const val ASSET_DICT = "models/ppocr_keys_v1.txt"

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dict: List<String> = emptyList()

    private val lock = Any()

    init {
        // 加载 OpenCV 原生库（AAR 内置 opencv_java4）
        runCatching {
            if (!OpenCVLoader.initLocal()) System.loadLibrary("opencv_java4")
        }
    }

    /** 离线模型是否随包内置 */
    fun isAvailable(context: Context): Boolean = try {
        val list = context.assets.list("models")
        list != null && list.contains("ch_PP-OCRv4_det_infer.onnx") &&
            list.contains("ch_PP-OCRv4_rec_infer.onnx") &&
            list.contains("ppocr_keys_v1.txt")
    } catch (e: Exception) {
        false
    }

    private fun ensureInit(context: Context) {
        if (detSession != null && recSession != null) return
        synchronized(lock) {
            if (detSession != null && recSession != null) return
            try {
                val e = OrtEnvironment.getEnvironment()
                val detBytes = context.assets.open(ASSET_DET).use { it.readBytes() }
                val recBytes = context.assets.open(ASSET_REC).use { it.readBytes() }
                val dictText = context.assets.open(ASSET_DICT).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
                env = e
                detSession = e.createSession(detBytes, OrtSession.SessionOptions())
                recSession = e.createSession(recBytes, OrtSession.SessionOptions())
                dict = dictText.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            } catch (t: Throwable) {
                release()
                throw IOException("离线模型加载失败：${t.message}", t)
            }
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { detSession?.close() }
            runCatching { recSession?.close() }
            detSession = null
            recSession = null
        }
    }

    fun recognize(bitmap: Bitmap): OcrResult {
        synchronized(lock) {
            ensureInit(App.context)
            val det = detSession ?: throw IOException("离线模型未初始化")
            val rec = recSession ?: throw IOException("离线模型未初始化")
            val dictArr = dict
            if (dictArr.isEmpty()) throw IOException("离线字典为空")

            // ---------- 1. det：缩放 + 归一化 + 推理 ----------
            val maxSide = max(bitmap.width, bitmap.height)
            val scale = DET_MAX_SIDE.toFloat() / maxSide
            val newW = (bitmap.width * scale).toInt().let { (it + 31) / 32 * 32 }.coerceAtLeast(32)
            val newH = (bitmap.height * scale).toInt().let { (it + 31) / 32 * 32 }.coerceAtLeast(32)

            val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            val pixels = IntArray(newW * newH)
            scaled.getPixels(pixels, 0, newW, 0, 0, newW, newH)

            val detInput = FloatArray(3 * newW * newH)
            val n = newW * newH
            for (i in 0 until n) {
                val p = pixels[i]
                detInput[i] = (((p shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
                detInput[n + i] = (((p shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
                detInput[2 * n + i] = ((p and 0xFF) / 255f - 0.406f) / 0.225f
            }

            val detName = det.inputNames.first()
            val detTensor = OnnxTensor.createTensor(
                env!!, FloatBuffer.wrap(detInput),
                longArrayOf(1, 3, newH.toLong(), newW.toLong())
            )
            val detOut = try {
                det.run(mapOf(detName to detTensor)).use { res ->
                    val info = res.get(0).info as TensorInfo
                    val shape = info.shape
                    Triple(shape, OnnxOutputFlattener.flatten(res.get(0).value), info)
                }
            } finally {
                // 输入张量用完即关，避免反复识别时 native 内存累积
                detTensor.close()
            }
            val detShape = detOut.first
            val detFloats = detOut.second
            val ph = detShape[2].toInt()
            val pw = detShape[3].toInt()

            // ---------- 2. DB 后处理：概率图 → 阈值 → 膨胀 → 轮廓 ----------
            val bin = Mat()
            try {
                val probMat = Mat(ph, pw, CvType.CV_32FC1)
                probMat.put(0, 0, detFloats)
                val up = Mat()
                Imgproc.resize(probMat, up, Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                probMat.release()
                Imgproc.threshold(up, bin, DET_THRESH.toDouble(), 1.0, Imgproc.THRESH_BINARY)
                up.release()
                bin.convertTo(bin, CvType.CV_8UC1, 255.0)

                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.dilate(bin, bin, kernel)
                kernel.release()

                val contours = ArrayList<MatOfPoint>()
                val hierarchy = Mat()
                Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                hierarchy.release()

                val boxes = extractBoxes(contours, newW, newH)
                contours.forEach { it.release() }
                if (boxes.isEmpty()) {
                    return OcrResult(emptyList(), imageWidth = bitmap.width, imageHeight = bitmap.height)
                }

                // ---------- 3. 行合并 + 边界修正 ----------
                val merged = mergeRows(boxes)
                // 灰度二值化（Otsu）用于精确边界
                val gray = Mat()
                val scaledMat = Mat()
                Utils.bitmapToMat(scaled, scaledMat)
                Imgproc.cvtColor(scaledMat, gray, Imgproc.COLOR_RGBA2GRAY)
                scaledMat.release()
                val textBin = Mat()
                Imgproc.threshold(gray, textBin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                gray.release()

                val rows = merged.mapNotNull { refineRow(textBin, it, newW, newH) }
                textBin.release()

                // ---------- 4. 逐行识别 ----------
                val lines = mutableListOf<OcrLine>()
                val scaleX = bitmap.width.toFloat() / newW
                val scaleY = bitmap.height.toFloat() / newH
                for (r in rows) {
                    // 行切分（超长行）
                    val pieces = splitRow(r)
                    for ((px0, px1) in pieces) {
                        val text = recognizeRow(bitmap, rec, dictArr, px0, px1, r[1], r[3], scaleX, scaleY)
                        if (text.isNotBlank()) {
                            val ox0 = (px0 * scaleX).toInt().coerceIn(0, bitmap.width - 1)
                            val oy0 = (r[1] * scaleY).toInt().coerceIn(0, bitmap.height - 1)
                            val ox1 = (px1 * scaleX).toInt().coerceIn(0, bitmap.width)
                            val oy1 = (r[3] * scaleY).toInt().coerceIn(0, bitmap.height)
                            lines.add(
                                OcrLine(
                                    text,
                                    0.0,
                                    listOf(
                                        ox0 / bitmap.width.toFloat(), oy0 / bitmap.height.toFloat(),
                                        ox1 / bitmap.width.toFloat(), oy0 / bitmap.height.toFloat(),
                                        ox1 / bitmap.width.toFloat(), oy1 / bitmap.height.toFloat(),
                                        ox0 / bitmap.width.toFloat(), oy1 / bitmap.height.toFloat()
                                    )
                                )
                            )
                        }
                    }
                }

                return OcrResult(
                    lines = lines,
                    modelName = "PP-OCRv4-mobile（离线）",
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    isCloud = false
                )
            } finally {
                bin.release()
                // createScaledBitmap 在尺寸相同时会返回原图引用，不能回收调用方传入的位图
                if (!scaled.isRecycled && scaled !== bitmap) scaled.recycle()
            }
        }
    }

    // ---------------------------------------------------------------
    // 检测框 → 行合并 → 边界修正
    // ---------------------------------------------------------------

    private class DetBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val cy: Int)

    /** minAreaRect 扩展（unclip）后取包围盒；轮廓由调用方统一释放 */
    private fun extractBoxes(contours: List<MatOfPoint>, newW: Int, newH: Int): List<DetBox> {
        val out = mutableListOf<DetBox>()
        val minSidePx = max(10f, min(newW, newH) * 0.01f)
        for (c in contours) {
            val pts = c.toArray()
            if (pts.size < 4) continue
            val pts2f = MatOfPoint2f(*pts)
            val r = try {
                Imgproc.minAreaRect(pts2f)
            } finally {
                pts2f.release()
            }
            val rw = r.size.width
            val rh = r.size.height
            if (rw * rh < 3.0 || min(rw, rh) < minSidePx) continue
            if (rw * rh > newW.toDouble() * newH.toDouble() * 0.9) continue

            // unclip：以中心扩展，保持角度
            val nw = (rw * UNCLIP_RATIO).coerceAtMost(newW.toDouble())
            val nh = (rh * UNCLIP_RATIO).coerceAtMost(newH.toDouble())
            val rad = Math.toRadians(r.angle)
            val cos = kotlin.math.cos(rad)
            val sin = kotlin.math.sin(rad)
            val hw = nw / 2
            val hh = nh / 2
            val cx = r.center.x
            val cy = r.center.y
            val corners = listOf(
                Point(cx - hw * cos - hh * sin, cy - hw * sin + hh * cos),
                Point(cx + hw * cos - hh * sin, cy + hw * sin + hh * cos),
                Point(cx + hw * cos + hh * sin, cy + hw * sin - hh * cos),
                Point(cx - hw * cos + hh * sin, cy - hw * sin - hh * cos)
            )
            val xs = corners.map { it.x.coerceIn(0.0, newW - 1.0) }
            val ys = corners.map { it.y.coerceIn(0.0, newH - 1.0) }
            out.add(
                DetBox(
                    xs.min().toInt(), ys.min().toInt(),
                    xs.max().toInt(), ys.max().toInt(),
                    (ys.min() + ys.max()).toInt() / 2
                )
            )
        }
        return out
    }

    /** 按 y 中心分组为行；行内 x 重叠或间隙 < 行高 的框合并为整行包围盒 */
    private fun mergeRows(boxes: List<DetBox>): List<IntArray> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedBy { it.cy }
        val groups = mutableListOf<MutableList<DetBox>>()
        for (b in sorted) {
            val last = groups.lastOrNull()?.lastOrNull()
            if (last != null && abs(b.cy - last.cy) < 25) {
                groups.last().add(b)
            } else {
                groups.add(mutableListOf(b))
            }
        }
        val out = mutableListOf<IntArray>()
        for (g in groups) {
            g.sortBy { it.x0 }
            var cx0 = g[0].x0
            var cy0 = g[0].y0
            var cx1 = g[0].x1
            var cy1 = g[0].y1
            for (b in g.drop(1)) {
                val rowH = max(cy1 - cy0, b.y1 - b.y0).coerceAtLeast(1)
                if (b.x0 - cx1 < rowH) { // 重叠或间隙小 → 合并
                    cx0 = min(cx0, b.x0)
                    cy0 = min(cy0, b.y0)
                    cx1 = max(cx1, b.x1)
                    cy1 = max(cy1, b.y1)
                } else {
                    out.add(intArrayOf(cx0, cy0, cx1, cy1))
                    cx0 = b.x0; cy0 = b.y0; cx1 = b.x1; cy1 = b.y1
                }
            }
            out.add(intArrayOf(cx0, cy0, cx1, cy1))
        }
        return out
    }

    /**
     * 边界修正：在外扩搜索区内用文字像素（二值图）投影求真实边界。
     * det 框常漏掉行首尾字符，搜索区必须大于 det 框。
     */
    private fun refineRow(bin: Mat, row: IntArray, newW: Int, newH: Int): IntArray? {
        val h = max(1, row[3] - row[1])
        val margin = max(h * 1.5f, 30f).toInt()
        val sx0 = max(0, row[0] - margin).coerceAtMost(newW - 1)
        val sy0 = max(0, row[1] - margin).coerceAtMost(newH - 1)
        val sx1 = min(newW, row[2] + margin).coerceAtLeast(sx0 + 1)
        val sy1 = min(newH, row[3] + margin).coerceAtLeast(sy0 + 1)

        val strip = bin.submat(sy0, sy1, sx0, sx1)
        val colSum = Mat()
        val rowSum = Mat()
        Core.reduce(strip, colSum, 0, Core.REDUCE_SUM, CvType.CV_32S)
        Core.reduce(strip, rowSum, 1, Core.REDUCE_SUM, CvType.CV_32S)
        val colData = IntArray(sx1 - sx0)
        val rowData = IntArray(sy1 - sy0)
        colSum.get(0, 0, colData)
        rowSum.get(0, 0, rowData)
        colSum.release()
        rowSum.release()
        strip.release()

        var cx0 = -1
        var cx1 = -1
        for (i in colData.indices) {
            if (colData[i] > 0) {
                if (cx0 < 0) cx0 = i
                cx1 = i
            }
        }
        var cy0 = -1
        var cy1 = -1
        for (i in rowData.indices) {
            if (rowData[i] > 0) {
                if (cy0 < 0) cy0 = i
                cy1 = i
            }
        }
        if (cx0 < 0 || cy0 < 0) return row // 兜底：用合并框

        val pad = 4
        return intArrayOf(
            max(0, sx0 + cx0 - pad), max(0, sy0 + cy0 - pad),
            min(newW, sx0 + cx1 + 1 + pad), min(newH, sy0 + cy1 + 1 + pad)
        )
    }

    /** 超长行等分切分（带 6% 重叠） */
    private fun splitRow(row: IntArray): List<Pair<Int, Int>> {
        val w = row[2] - row[0]
        val h = max(1, row[3] - row[1])
        val ratio = w.toFloat() / h
        if (ratio <= SPLIT_RATIO) return listOf(row[0] to row[2])
        val n = kotlin.math.ceil(ratio / PIECE_RATIO).toInt().coerceAtLeast(2)
        val pieces = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until n) {
            var a = row[0] + w * i / n
            var b = row[0] + w * (i + 1) / n
            val ov = (w / n * 0.06f).toInt()
            a = max(row[0], a - ov)
            b = min(row[2], b + ov)
            if (b - a >= 8) pieces.add(a to b)
        }
        return pieces
    }

    // ---------------------------------------------------------------
    // rec：裁剪 → 固定 320×48 → 推理 → CTC 解码
    // ---------------------------------------------------------------

    private fun recognizeRow(
        bitmap: Bitmap,
        session: OrtSession,
        dict: List<String>,
        px0: Int, px1: Int, py0: Int, py1: Int,
        scaleX: Float, scaleY: Float
    ): String {
        val e = env ?: return ""
        val ox0 = (px0 * scaleX).toInt().coerceIn(0, bitmap.width - 1)
        val oy0 = (py0 * scaleY).toInt().coerceIn(0, bitmap.height - 1)
        val ox1 = (px1 * scaleX).toInt().coerceIn(ox0 + 1, bitmap.width)
        val oy1 = (py1 * scaleY).toInt().coerceIn(oy0 + 1, bitmap.height)
        val ow = ox1 - ox0
        val oh = oy1 - oy0
        if (ow < 4 || oh < 4) return ""

        val crop = Bitmap.createBitmap(bitmap, ox0, oy0, ow, oh)
        val recBmp = Bitmap.createScaledBitmap(crop, REC_WIDTH, REC_HEIGHT, true)
        if (recBmp !== crop) crop.recycle()

        val input = FloatArray(3 * REC_WIDTH * REC_HEIGHT)
        val n = REC_WIDTH * REC_HEIGHT
        val px = IntArray(n)
        recBmp.getPixels(px, 0, REC_WIDTH, 0, 0, REC_WIDTH, REC_HEIGHT)
        if (recBmp !== bitmap) recBmp.recycle()
        for (i in 0 until n) {
            val p = px[i]
            input[i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            input[n + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            input[2 * n + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
        }

        val recName = session.inputNames.first()
        val recTensor = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, REC_HEIGHT.toLong(), REC_WIDTH.toLong())
        )
        val result = try {
            session.run(mapOf(recName to recTensor))
        } finally {
            recTensor.close()
        }
        return result.use { res ->
            val info = res.get(0).info as TensorInfo
            val shape = info.shape
            val T = shape[1].toInt()
            val C = shape[2].toInt()
            val logits = OnnxOutputFlattener.flatten(res.get(0).value)
            decodeCtc(logits, T, C, dict)
        }
    }

    private fun decodeCtc(logits: FloatArray, T: Int, C: Int, dict: List<String>): String {
        if (C <= 1) return ""
        // PaddleOCR 的 CTC blank 在字符表首位（index 0），字符从 index 1 开始
        val blank = 0
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until T) {
            val base = t * C
            var best = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (c in 0 until C) {
                val v = logits[base + c]
                if (v > bestVal) {
                    bestVal = v
                    best = c
                }
            }
            if (best != blank && best != prev && best - 1 < dict.size) {
                sb.append(dict[best - 1])
            }
            prev = best
        }
        return sb.toString()
    }

}
