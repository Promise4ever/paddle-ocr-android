package com.example.paddleocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.paddleocr.data.HistoryEntity
import com.example.paddleocr.data.ResultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 带日期分组头的列表行 */
sealed class HistoryRow {
    data class Header(val label: String) : HistoryRow()
    data class Entry(val entity: HistoryEntity) : HistoryRow()
}

data class HistoryUiState(
    val rows: List<HistoryRow> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val total: Int = 0,
    val searching: Boolean = false
)

/**
 * 历史记录：搜索、分页、收藏、删除、清空。
 */
class HistoryViewModel : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private var page = 0
    private val pageSize = 20
    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        // 取消上一次加载，避免 reload 与 loadMore 并发导致分页重复/回退
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true)
            page = 0
            val dao = ResultRepository.dao()
            val total = if (_state.value.query.isBlank()) dao.count()
            else dao.searchCount(_state.value.query)
            val pageData = fetchPage(0)
            _state.value = _state.value.copy(
                rows = buildRows(pageData),
                loading = false,
                hasMore = pageData.size == pageSize,
                total = total,
                searching = _state.value.query.isNotBlank()
            )
        }
    }

    fun search(q: String) {
        _state.value = _state.value.copy(query = q)
        reload()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = s.copy(loading = true)
            val next = fetchPage(page + 1)
            val all = s.rows + buildRows(next, s.rows)
            _state.value = _state.value.copy(
                rows = all,
                loading = false,
                hasMore = next.size == pageSize
            )
            page += 1
        }
    }

    private suspend fun fetchPage(p: Int): List<HistoryEntity> {
        val dao = ResultRepository.dao()
        return if (_state.value.query.isBlank()) dao.page(pageSize, p * pageSize)
        else dao.search(_state.value.query, pageSize, p * pageSize)
    }

    private fun buildRows(data: List<HistoryEntity>, existing: List<HistoryRow> = emptyList()): List<HistoryRow> {
        val out = mutableListOf<HistoryRow>()
        val lastDate = existing.filterIsInstance<HistoryRow.Entry>()
            .lastOrNull()?.entity?.let { dateLabel(it.time) }
        var prevDate = lastDate
        for (e in data) {
            val label = dateLabel(e.time)
            if (label != prevDate) {
                out.add(HistoryRow.Header(label))
                prevDate = label
            }
            out.add(HistoryRow.Entry(e))
        }
        return out
    }

    private fun dateLabel(time: Long): String =
        java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.getDefault())
            .format(java.util.Date(time))

    fun toggleFavorite(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = ResultRepository.dao()
            val e = dao.byId(id) ?: return@launch
            dao.setFavorite(id, !e.favorite)
            reload()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            ResultRepository.deleteEntry(id)
            reload()
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            ResultRepository.clearAll()
            reload()
        }
    }
}
