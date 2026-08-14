package com.example.paddleocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.paddleocr.data.ResultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

data class MainUiState(
    val isCloud: Boolean = false,
    val modelName: String = "",
    val used: Int = 0,
    val remaining: Int = 0,
    val limit: Int = Prefs.DEFAULT_QUOTA_LIMIT,
    val exhausted: Boolean = false,
    val lastResultId: Long? = null,
    val lastResultTime: Long = 0,
    val lastResultPreview: String = "",
    val offlineAvailable: Boolean = true
)

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        // 一次性迁移旧版 SharedPreferences 历史到 Room
        viewModelScope.launch(Dispatchers.IO) {
            ResultRepository.migrateLegacyHistoryIfNeeded()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val isCloud = Prefs.mode == ApiMode.AI_STUDIO && Prefs.token.isNotBlank()
            val model = Prefs.modelName.ifBlank { "-" }
            val last = ResultRepository.dao().latest()
            val preview = last?.linesJson?.let { raw ->
                runCatching {
                    val arr = JSONArray(raw)
                    val n = minOf(4, arr.length())
                    (0 until n).mapNotNull {
                        arr.optJSONObject(it)?.optString("text")
                    }.joinToString("\n")
                }.getOrDefault("")
            } ?: ""
            _state.value = MainUiState(
                isCloud = isCloud,
                modelName = model,
                used = if (isCloud) Prefs.quotaUsedToday(model) else 0,
                remaining = if (isCloud) Prefs.quotaRemaining(model) else 0,
                limit = Prefs.quotaLimit,
                exhausted = isCloud && Prefs.isQuotaExhausted(model),
                lastResultId = last?.id,
                lastResultTime = last?.time ?: 0L,
                lastResultPreview = preview,
                offlineAvailable = OfflineOcrEngine.isAvailable(App.context)
            )
        }
    }

    /** 重置本地配额统计（切换账号/想重新统计时） */
    fun resetQuota() {
        Prefs.resetQuotaState()
        refresh()
    }
}
