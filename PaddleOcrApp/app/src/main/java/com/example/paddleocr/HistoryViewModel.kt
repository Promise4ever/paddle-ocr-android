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
    data class Entry(val entity: HistoryEntity, val showFavoriteTime: Boolean = false) : HistoryRow()
}

enum class HistoryCategory { ALL, FAVORITES }

data class HistoryUiState(
    val rows: List<HistoryRow> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val total: Int = 0,
    val searching: Boolean = false,
    val category: HistoryCategory = HistoryCategory.ALL
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
            val total = countResults(dao)
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

    fun setCategory(category: HistoryCategory) {
        if (_state.value.category == category) return
        _state.value = _state.value.copy(category = category)
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
        val query = _state.value.query
        return when (_state.value.category) {
            HistoryCategory.ALL -> if (query.isBlank()) {
                dao.page(pageSize, p * pageSize)
            } else {
                dao.search(query, pageSize, p * pageSize)
            }
            HistoryCategory.FAVORITES -> if (query.isBlank()) {
                dao.favoritePage(pageSize, p * pageSize)
            } else {
                dao.searchFavorites(query, pageSize, p * pageSize)
            }
        }
    }

    private suspend fun countResults(dao: com.example.paddleocr.data.HistoryDao): Int {
        val query = _state.value.query
        return when (_state.value.category) {
            HistoryCategory.ALL -> if (query.isBlank()) dao.count() else dao.searchCount(query)
            HistoryCategory.FAVORITES -> if (query.isBlank()) {
                dao.favoriteCount()
            } else {
                dao.searchFavoriteCount(query)
            }
        }
    }

    private fun buildRows(data: List<HistoryEntity>, existing: List<HistoryRow> = emptyList()): List<HistoryRow> {
        val out = mutableListOf<HistoryRow>()
        val favoriteMode = _state.value.category == HistoryCategory.FAVORITES
        fun groupTime(e: HistoryEntity) = if (favoriteMode) e.favoritedAt ?: e.time else e.time
        val lastDate = existing.filterIsInstance<HistoryRow.Entry>()
            .lastOrNull()?.entity?.let { dateLabel(groupTime(it)) }
        var prevDate = lastDate
        for (e in data) {
            val label = dateLabel(groupTime(e))
            if (label != prevDate) {
                out.add(HistoryRow.Header(label))
                prevDate = label
            }
            out.add(HistoryRow.Entry(e, showFavoriteTime = favoriteMode))
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
            val favorite = !e.favorite
            dao.setFavorite(id, favorite, if (favorite) System.currentTimeMillis() else null)
            reload()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            ResultRepository.deleteEntry(id)
            reload()
        }
    }

    fun clearUnfavorited() {
        viewModelScope.launch(Dispatchers.IO) {
            ResultRepository.clearUnfavorited()
            reload()
        }
    }
}
