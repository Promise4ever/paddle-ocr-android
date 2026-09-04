package com.example.paddleocr

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paddleocr.data.HistoryEntity
import com.example.paddleocr.databinding.ActivityHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var viewModel: HistoryViewModel
    private var adapter: HistoryAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[HistoryViewModel::class.java]

        binding.bottomNav.setOnItemSelectedListener { item ->
            BottomNav.onTabSelected(this, item.itemId)
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_history

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_history) {
                confirmClear()
                true
            } else false
        }

        val layoutManager = LinearLayoutManager(this)
        binding.historyList.layoutManager = layoutManager
        adapter = HistoryAdapter(
            scope = lifecycleScope,
            onItemClick = { id -> ResultActivity.start(this, id) },
            onFavorite = { id -> viewModel.toggleFavorite(id) },
            onDelete = { id -> viewModel.delete(id) }
        )
        binding.historyList.adapter = adapter
        binding.historyList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = layoutManager.findLastVisibleItemPosition()
                val total = layoutManager.itemCount
                if (dy > 0 && last >= total - 3) viewModel.loadMore()
            }
        })

        binding.historyCategoryToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setCategory(
                if (checkedId == R.id.btnHistoryFavorites) {
                    HistoryCategory.FAVORITES
                } else {
                    HistoryCategory.ALL
                }
            )
        }

        binding.searchInput.addTextChangedListener(
            object : android.text.TextWatcher {
                private var searchJob: kotlinx.coroutines.Job? = null
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        kotlinx.coroutines.delay(300) // 防抖
                        viewModel.search(s?.toString()?.trim().orEmpty())
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )

        lifecycleScope.launch {
            viewModel.state.collect { s ->
                adapter?.submit(s.rows)
                binding.emptyView.text = if (s.category == HistoryCategory.FAVORITES) {
                    getString(R.string.history_favorite_empty)
                } else {
                    getString(R.string.history_empty)
                }
                binding.emptyView.visibility =
                    if (s.rows.isEmpty() && !s.loading) View.VISIBLE else View.GONE
                binding.historyList.visibility =
                    if (s.rows.isEmpty()) View.GONE else View.VISIBLE
                binding.historyTotal.text = if (s.category == HistoryCategory.FAVORITES) {
                    getString(R.string.history_favorite_total, s.total)
                } else {
                    getString(R.string.history_total, s.total)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reload()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_clear_title)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.history_clear_ok) { _, _ -> viewModel.clearUnfavorited() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

class HistoryAdapter(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onItemClick: (Long) -> Unit,
    private val onFavorite: (Long) -> Unit,
    private val onDelete: (Long) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<HistoryRow> = emptyList()

    fun submit(newRows: List<HistoryRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is HistoryRow.Header) TYPE_HEADER else TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_history_header, parent, false))
        } else {
            EntryHolder(inflater.inflate(R.layout.item_history, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRow.Header -> (holder as HeaderHolder).label.text = row.label
            is HistoryRow.Entry -> (holder as EntryHolder).bind(row.entity, row.showFavoriteTime)
        }
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.itemHeader)
    }

    private inner class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val time: TextView = view.findViewById(R.id.itemTime)
        private val preview: TextView = view.findViewById(R.id.itemPreview)
        private val stats: TextView = view.findViewById(R.id.itemStats)
        private val thumb: ImageView = view.findViewById(R.id.itemThumb)
        private val favBtn: ImageView = view.findViewById(R.id.itemFavorite)
        private val delBtn: ImageView = view.findViewById(R.id.itemDelete)

        fun bind(e: HistoryEntity, showFavoriteTime: Boolean) {
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            time.text = if (showFavoriteTime) {
                itemView.context.getString(
                    R.string.history_favorited_at,
                    formatter.format(Date(e.favoritedAt ?: e.time))
                )
            } else {
                formatter.format(Date(e.time))
            }
            val text = runCatching {
                val arr = JSONArray(e.linesJson)
                val n = minOf(3, arr.length())
                (0 until n).mapNotNull { arr.optJSONObject(it)?.optString("text") }
                    .joinToString("\n")
            }.getOrDefault("")
            preview.text = PlainText.clean(text.ifEmpty { e.markdown?.take(200) ?: "" })
            val lineCount = runCatching { JSONArray(e.linesJson).length() }.getOrDefault(0)
            stats.text = itemView.context.getString(R.string.history_stats, lineCount)

            thumb.visibility = if (e.thumbnailPath != null) View.VISIBLE else View.GONE
            if (e.thumbnailPath != null) {
                val path = e.thumbnailPath
                thumb.setImageDrawable(null)
                scope.launch(Dispatchers.IO) {
                    val bmp = runCatching {
                        val f = File(path)
                        if (f.exists()) BitmapFactory.decodeFile(path) else null
                    }.getOrNull()
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            if (itemView.tag == path) thumb.setImageBitmap(bmp)
                        }
                    }
                }
                itemView.tag = path
            }

            favBtn.setImageResource(
                if (e.favorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )
            favBtn.setOnClickListener { onFavorite(e.id) }
            delBtn.setOnClickListener { onDelete(e.id) }
            itemView.setOnClickListener { onItemClick(e.id) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
    }
}
