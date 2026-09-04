package com.example.paddleocr

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.paddleocr.data.ResultRepository
import com.example.paddleocr.databinding.ActivityBatchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchBinding
    private lateinit var viewModel: BatchViewModel
    private var adapter: BatchAdapter? = null

    companion object {
        fun start(context: Context, uris: List<Uri>) {
            context.startActivity(
                Intent(context, BatchActivity::class.java)
                    .putExtra(EXTRA_URIS, uris.map { it.toString() }.toTypedArray())
            )
        }

        private const val EXTRA_URIS = "extra_uris"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.batch_title)

        viewModel = ViewModelProvider(this)[BatchViewModel::class.java]

        val uris = intent.getStringArrayExtra(EXTRA_URIS)
        if (uris.isNullOrEmpty()) {
            finish()
            return
        }

        binding.btnCancel.setOnClickListener { viewModel.cancel() }
        binding.btnExportAll.setOnClickListener { exportAll() }
        binding.btnRetryAll.setOnClickListener { retryFailed() }

        binding.batchList.layoutManager = LinearLayoutManager(this)
        adapter = BatchAdapter(
            scope = lifecycleScope,
            onItemClick = { id -> ResultActivity.start(this, id) },
            onRetry = { uri -> viewModel.retry(uri) }
        )
        binding.batchList.adapter = adapter

        lifecycleScope.launch {
            viewModel.state.collectLatest { s ->
                adapter?.submit(s.items)
                binding.progressBar.isVisible = s.running
                binding.btnCancel.isVisible = s.running
                binding.progressText.text = s.progressMsg
                binding.summaryRow.isVisible = !s.running && s.doneCount > 0
                if (!s.running && s.doneCount > 0) {
                    binding.summaryText.text = getString(
                        R.string.batch_summary, s.successCount, s.failCount
                    )
                }
                binding.btnRetryAll.isVisible =
                    !s.running && s.failCount > 0
            }
        }

        // 首次进入自动开始
        if (savedInstanceState == null) {
            viewModel.start(uris.map { Uri.parse(it) })
        }
    }

    private fun exportAll() {
        val state = viewModel.state.value
        val ids = state.items.filter { it.status == BatchStatus.SUCCESS }
            .mapNotNull { it.historyId }
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.batch_nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val dao = ResultRepository.dao()
            var n = 0
            for (id in ids) {
                val e = dao.byId(id) ?: continue
                sb.append("===== 第 ${n + 1} 张 =====\n")
                val res = ResultRepository.entityToResult(e)
                sb.append(PlainText.convert(res)).append("\n\n")
                n++
            }
            val file = ResultRepository.cacheFile("OCR_BATCH_${System.currentTimeMillis()}.txt")
            file.writeText(sb.toString(), Charsets.UTF_8)
            withContext(Dispatchers.Main) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@BatchActivity, "$packageName.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
            }
        }
    }

    private fun retryFailed() {
        val failed = viewModel.state.value.items
            .filter { it.status == BatchStatus.FAILED }
        if (failed.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.batch_retry_title)
            .setMessage(getString(R.string.batch_retry_confirm, failed.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.start(failed.map { Uri.parse(it.uri) })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        // 识别中返回视为放弃剩余任务
        viewModel.cancel()
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class BatchAdapter(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onItemClick: (Long) -> Unit,
    private val onRetry: (String) -> Unit
) : RecyclerView.Adapter<BatchAdapter.Holder>() {

    private var items: List<BatchItem> = emptyList()

    fun submit(newItems: List<BatchItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_batch, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(items[position], position)

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.itemThumb)
        private val name: TextView = view.findViewById(R.id.itemName)
        private val status: TextView = view.findViewById(R.id.itemStatus)
        private val retry: TextView = view.findViewById(R.id.itemRetry)

        fun bind(item: BatchItem, position: Int) {
            name.text = item.displayName
            val ctx = itemView.context
            status.text = when (item.status) {
                BatchStatus.QUEUED -> ctx.getString(R.string.batch_status_queued)
                BatchStatus.RUNNING -> ctx.getString(R.string.batch_status_running)
                BatchStatus.SUCCESS -> ctx.getString(R.string.batch_status_success)
                BatchStatus.FAILED -> item.error ?: ctx.getString(R.string.batch_status_failed)
                BatchStatus.SKIPPED -> ctx.getString(R.string.batch_status_cancelled)
            }
            status.setTextColor(
                when (item.status) {
                    BatchStatus.SUCCESS -> ctx.getColor(R.color.batch_ok)
                    BatchStatus.FAILED, BatchStatus.SKIPPED -> ctx.getColor(R.color.batch_err)
                    else -> ctx.getColor(R.color.text_secondary)
                }
            )
            retry.isVisible = item.status == BatchStatus.FAILED
            retry.setOnClickListener { onRetry(item.uri) }

            thumb.setImageDrawable(null)
            val thumbKey = item.uri
            thumb.tag = thumbKey
            scope.launch(Dispatchers.IO) {
                val bmp = runCatching {
                    ImageUtils.loadBitmap(ctx, Uri.parse(item.uri), maxDim = 256)
                }.getOrNull()
                if (bmp != null) {
                    withContext(Dispatchers.Main) {
                        // 位置与内容都匹配才设置，避免复用 View 时显示旧图
                        if (layoutPosition == position && thumb.tag == thumbKey) {
                            thumb.setImageBitmap(bmp)
                        }
                    }
                }
            }

            itemView.setOnClickListener {
                item.historyId?.let(onItemClick)
            }
        }
    }
}
