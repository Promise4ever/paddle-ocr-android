package com.example.paddleocr

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.paddleocr.data.ResultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BatchStatus { QUEUED, RUNNING, SUCCESS, FAILED, SKIPPED }

data class BatchItem(
    val uri: String,
    val displayName: String,
    val status: BatchStatus = BatchStatus.QUEUED,
    val historyId: Long? = null,
    val error: String? = null
)

data class BatchUiState(
    val items: List<BatchItem> = emptyList(),
    val running: Boolean = false,
    val doneCount: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val progressMsg: String = ""
)

/**
 * 批量识别：多图依次全图识别（自动保存历史），支持取消。
 */
class BatchViewModel : ViewModel() {

    private val _state = MutableStateFlow(BatchUiState())
    val state: StateFlow<BatchUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(uris: List<Uri>) {
        if (_state.value.running) return
        val items = uris.map { uri ->
            BatchItem(
                uri = uri.toString(),
                displayName = uri.lastPathSegment ?: "图片"
            )
        }
        _state.value = BatchUiState(items = items, running = true, progressMsg = "准备中…")

        job = viewModelScope.launch(Dispatchers.IO) {
            var done = 0
            var ok = 0
            var fail = 0
            val updated = items.toMutableList()
            for (i in updated.indices) {
                currentCoroutineContext().ensureActive()
                val item = updated[i]
                updated[i] = item.copy(status = BatchStatus.RUNNING)
                _state.value = _state.value.copy(
                    items = updated.toList(),
                    progressMsg = "正在识别 ${i + 1}/${items.size}…"
                )
                try {
                    val bitmap = ImageUtils.loadBitmap(App.context, Uri.parse(item.uri), maxDim = 4096)
                    val result = OcrClient.recognize(bitmap) { msg ->
                        _state.value = _state.value.copy(progressMsg = "第 ${i + 1} 张：$msg")
                    }
                    if (result.lines.isEmpty() && result.markdown.isNullOrBlank()) {
                        throw Exception("未识别到文字")
                    }
                    val id = ResultRepository.save(result, bitmap)
                    if (!bitmap.isRecycled) bitmap.recycle()
                    updated[i] = updated[i].copy(status = BatchStatus.SUCCESS, historyId = id)
                    ok++
                } catch (e: CancellationException) {
                    updated[i] = updated[i].copy(status = BatchStatus.SKIPPED, error = "已取消")
                    throw e
                } catch (e: Exception) {
                    updated[i] = updated[i].copy(
                        status = BatchStatus.FAILED,
                        error = e.message ?: "识别失败"
                    )
                    fail++
                }
                done++
                _state.value = _state.value.copy(
                    items = updated.toList(),
                    doneCount = done,
                    successCount = ok,
                    failCount = fail
                )
            }
            _state.value = _state.value.copy(
                running = false,
                progressMsg = "全部完成：成功 $ok 张，失败 $fail 张"
            )
        }
    }

    fun cancel() {
        job?.cancel()
        val s = _state.value
        // 取消时把未完成的条目标记为 SKIPPED 并刷新界面，避免残留"识别中/排队中"
        val updated = s.items.map {
            if (it.status == BatchStatus.QUEUED || it.status == BatchStatus.RUNNING) {
                it.copy(status = BatchStatus.SKIPPED, error = "已取消")
            } else it
        }
        _state.value = s.copy(
            items = updated,
            running = false,
            progressMsg = "已取消"
        )
    }

    /** 单张重试 */
    fun retry(uri: String) {
        val current = _state.value
        if (current.running) return
        start(listOf(Uri.parse(uri)))
    }
}
