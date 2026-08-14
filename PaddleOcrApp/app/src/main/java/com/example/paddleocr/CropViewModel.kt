package com.example.paddleocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.paddleocr.data.OcrResult
import com.example.paddleocr.data.ResultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class CropUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val ocrRunning: Boolean = false,
    val progressMsg: String = ""
)

/**
 * 裁剪页 ViewModel：负责图片解码（旋转后不重载）、OCR 任务与取消。
 * Bitmap 由本类持有，Activity 重建后仍可继续显示与识别。
 */
class CropViewModel(private val uriString: String) : ViewModel() {

    private val _state = MutableStateFlow(CropUiState())
    val state: StateFlow<CropUiState> = _state.asStateFlow()

    private var display: Bitmap? = null
    private var full: Bitmap? = null
    private var ocrJob: Job? = null

    fun loadImages(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val d = ImageUtils.loadBitmap(context, uri, maxDim = 1800)
                val f = ImageUtils.loadBitmap(context, uri, maxDim = 4096)
                display?.let { if (it !== d) it.recycle() }
                full?.let { if (it !== f) it.recycle() }
                display = d
                full = f
                _state.value = _state.value.copy(loading = false, loadError = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    loadError = e.message ?: "图片加载失败"
                )
            }
        }
    }

    fun displayBitmap(): Bitmap? = display

    fun fullBitmap(): Bitmap? = full

    /** 节流预览更新（避免拖动时高频裁剪大图） */
    private var lastPreviewAt = 0L
    private var previewJob: Job? = null

    fun updatePreview(rect: RectF, block: (Bitmap) -> Unit) {
        val bmp = display ?: return
        val now = System.currentTimeMillis()
        if (now - lastPreviewAt < 50) return
        lastPreviewAt = now
        previewJob?.cancel()
        // 预览裁剪放在 IO 线程，避免阻塞主线程；新事件到来时丢弃旧任务
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            val w = bmp.width
            val h = bmp.height
            val l = (rect.left * w).toInt().coerceIn(0, w - 1)
            val t = (rect.top * h).toInt().coerceIn(0, h - 1)
            val pw = (rect.width() * w).toInt().coerceIn(1, w - l)
            val ph = (rect.height() * h).toInt().coerceIn(1, h - t)
            runCatching {
                val preview = Bitmap.createBitmap(bmp, l, t, pw, ph)
                withContext(Dispatchers.Main) { block(preview) }
            }
        }
    }

    /**
     * 裁剪并识别；成功回调返回入库后的历史记录 id。
     */
    fun cropAndRecognize(rect: RectF, onSuccess: (Long) -> Unit, onError: (String) -> Unit) {
        val full = full ?: return
        val l = (rect.left * full.width).toInt().coerceIn(0, full.width - 1)
        val t = (rect.top * full.height).toInt().coerceIn(0, full.height - 1)
        val w = (rect.width() * full.width).toInt().coerceAtLeast(1)
        val h = (rect.height() * full.height).toInt().coerceAtLeast(1)
        if (w < 8 || h < 8) {
            onError("识别区域过小，请重新框选")
            return
        }
        val cropped = runCatching {
            Bitmap.createBitmap(
                full, l, t,
                w.coerceAtMost(full.width - l),
                h.coerceAtMost(full.height - t)
            )
        }.getOrElse {
            onError("识别区域过小，请重新框选")
            return
        }

        _state.value = _state.value.copy(ocrRunning = true, progressMsg = "正在上传图片…")
        ocrJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = OcrClient.recognize(cropped) { msg ->
                    _state.value = _state.value.copy(progressMsg = msg)
                }
                if (result.lines.isEmpty() && result.markdown.isNullOrBlank()) {
                    throw IOException("未识别到文字")
                }
                val id = ResultRepository.save(result, cropped)
                _state.value = _state.value.copy(ocrRunning = false)
                withContext(Dispatchers.Main) { onSuccess(id) }
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(ocrRunning = false)
                withContext(Dispatchers.Main) { onError("已取消") }
            } catch (e: Exception) {
                _state.value = _state.value.copy(ocrRunning = false)
                // 诊断：完整异常栈落盘（vivo 等机型 logcat 不可见）
                runCatching {
                    val sw = java.io.StringWriter()
                    e.printStackTrace(java.io.PrintWriter(sw))
                    java.io.File(App.context.filesDir, "ocr_error.txt")
                        .writeText("${e.javaClass.simpleName}: ${e.message}\n${sw}")
                }
                withContext(Dispatchers.Main) { onError(e.message ?: "识别失败") }
            }
        }
    }

    fun cancelOcr() {
        ocrJob?.cancel()
        _state.value = _state.value.copy(ocrRunning = false)
    }

    override fun onCleared() {
        ocrJob?.cancel()
        display?.recycle()
        full?.recycle()
        super.onCleared()
    }

    class Factory(private val uriString: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CropViewModel(uriString) as T
    }
}
