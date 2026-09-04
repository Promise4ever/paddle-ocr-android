package com.example.paddleocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.paddleocr.data.HistoryEntity
import com.example.paddleocr.data.OcrResult
import com.example.paddleocr.data.ResultRepository
import com.example.paddleocr.databinding.ActivityResultBinding
import com.example.paddleocr.databinding.ItemResultTableBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private var entity: HistoryEntity? = null
    private var result: OcrResult? = null

    companion object {
        private const val EXTRA_ID = "extra_id"

        /** 通过历史记录 id 打开结果页（避免大结果走 Intent 触发 Binder 超限） */
        fun start(context: Context, id: Long) {
            context.startActivity(
                Intent(context, ResultActivity::class.java).putExtra(EXTRA_ID, id)
            )
        }
    }

    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val r = result ?: return@registerForActivityResult
        val mode = pendingExportMode ?: return@registerForActivityResult
        pendingExportMode = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = when (mode) {
                    "txt" -> Exporter.buildTxt(r)
                    "docx" -> Exporter.buildDocx(r)
                    else -> Exporter.buildMarkdown(r)
                }
                withContext(Dispatchers.Main) {
                    runCatching {
                        val out = contentResolver.openOutputStream(uri)
                            ?: throw IOException("无法打开目标文件")
                        out.use { target ->
                            file.inputStream().use { it.copyTo(target) }
                        }
                    }.onFailure {
                        Toast.makeText(this@ResultActivity, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
                    }.onSuccess {
                        Toast.makeText(this@ResultActivity, R.string.exported, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ResultActivity, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var pendingExportMode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.result_title)

        binding.btnCopy.setOnClickListener { copy() }
        binding.btnShare.setOnClickListener { share() }
        binding.btnOverlay.setOnClickListener {
            entity?.let { OverlayActivity.start(this, it.id) }
        }
        binding.btnExportTxt.setOnClickListener { export("txt") }
        binding.btnExportMd.setOnClickListener { export("md") }
        binding.btnExportDocx.setOnClickListener { export("docx") }

        binding.btnFormatView.setOnClickListener { showFormatView() }
        binding.btnPlainTextView.setOnClickListener { showPlainTextView() }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) {
            Toast.makeText(this, R.string.empty_result, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        load(id)
    }

    private fun load(id: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val e = ResultRepository.dao().byId(id)
            withContext(Dispatchers.Main) {
                if (e == null) {
                    Toast.makeText(this@ResultActivity, "记录不存在或已被删除", Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                entity = e
                result = ResultRepository.entityToResult(e)
                render()
            }
        }
    }

    private fun render() {
        val r = result ?: return
        val hasPolygon = r.lines.any { it.polygon != null }
        val blocks = FormattedContent.parse(r.formattedSource)
        val tableCount = blocks.count { it is FormattedBlock.Table }

        // 格式视图按识别顺序混排普通文字与原生表格；全文本视图只保留文字。
        binding.resultModeToggle.visibility = View.VISIBLE
        showFormatView()

        if (r.lines.isEmpty()) {
            binding.stats.text = if (tableCount > 0) {
                getString(R.string.result_table_stats, tableCount)
            } else {
                getString(R.string.empty_result)
            }
        } else {
            val avg = r.lines.map { it.confidence }.average()
            binding.stats.text = if (avg > 0.001) {
                getString(R.string.result_stats, r.lines.size, avg)
            } else {
                getString(R.string.result_stats_no_conf, r.lines.size)
            }
        }

        // 识别框叠加：仅当结果带坐标且保存了原图时可用
        binding.btnOverlay.visibility =
            if (hasPolygon && r.sourceImagePath != null) View.VISIBLE else View.GONE
        // 导出按钮
        binding.exportRow.visibility = View.VISIBLE
    }

    private fun showFormatView() {
        val r = result ?: return
        val blocks = FormattedContent.parse(r.formattedSource)
        binding.resultModeToggle.check(R.id.btnFormatView)
        binding.resultScroll.visibility = View.GONE
        binding.resultFormatted.visibility = View.VISIBLE
        renderFormatted(blocks)
    }

    private fun showPlainTextView() {
        val r = result ?: return
        binding.resultModeToggle.check(R.id.btnPlainTextView)
        binding.resultFormatted.visibility = View.GONE
        binding.resultScroll.visibility = View.VISIBLE
        binding.resultText.text = PlainText.convert(r).ifBlank { r.fullText }
    }

    private fun renderFormatted(blocks: List<FormattedBlock>) {
        binding.formattedContainer.removeAllViews()
        val dark = isDarkMode()
        blocks.forEach { block ->
            when (block) {
                is FormattedBlock.Text -> {
                    val text = PlainText.clean(block.source)
                    if (text.isNotBlank()) {
                        binding.formattedContainer.addView(TextView(this).apply {
                            this.text = text
                            textSize = 16f
                            setLineSpacing(dp(5).toFloat(), 1f)
                            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                            setTextIsSelectable(true)
                            setPadding(dp(4), dp(4), dp(4), dp(8))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(8) }
                        })
                    }
                }
                is FormattedBlock.Table -> addFormattedTable(block.table, dark)
            }
        }
        if (binding.formattedContainer.childCount == 0) {
            binding.formattedContainer.addView(TextView(this).apply {
                text = getString(R.string.empty_result)
                textSize = 16f
                setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
    }

    private fun addFormattedTable(table: MarkdownTable, dark: Boolean) {
        val item = ItemResultTableBinding.inflate(
            layoutInflater, binding.formattedContainer, false
        )
        item.tableTitle.text = table.title ?: getString(R.string.result_table_default_title)
        item.btnCopyTable.setOnClickListener { copyTable(table) }
        item.btnCorrectTable.setOnClickListener { correctTable(table) }
        item.tableLayout.removeAllViews()
        addTableRow(
            item.tableLayout, table.headers,
            header = true, dark = dark, rowIndex = 0
        )
        table.rows.forEachIndexed { index, row ->
            addTableRow(
                item.tableLayout, row,
                header = false, dark = dark, rowIndex = index + 1
            )
        }
        binding.formattedContainer.addView(item.root)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun addTableRow(
        parent: TableLayout,
        cells: List<String>,
        header: Boolean,
        dark: Boolean,
        rowIndex: Int
    ) {
        val row = TableRow(this)
        val count = maxOf(1, cells.size)
        repeat(count) { index ->
            val tv = TextView(this)
            tv.text = cells.getOrElse(index) { "" }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (header) 14f else 13f)
            tv.setTypeface(null, if (header) Typeface.BOLD else Typeface.NORMAL)
            tv.setPadding(dp(12), dp(10), dp(12), dp(10))
            tv.minWidth = dp(96)

            val bg = android.graphics.drawable.GradientDrawable().apply {
                val even = rowIndex % 2 == 0
                val bgColor = when {
                    header -> if (dark) "#1E3A5F" else "#E8EEF7"
                    even -> if (dark) "#1E1E1E" else "#FFFFFF"
                    else -> if (dark) "#262626" else "#F7F9FC"
                }
                val fgColor = if (header && !dark) "#14315C" else if (dark) "#E4E4E4" else "#1A1A1A"
                val borderColor = if (dark) "#3A3A3A" else "#DDE3EA"
                setColor(Color.parseColor(bgColor))
                setStroke(dp(1), Color.parseColor(borderColor))
                tv.setTextColor(Color.parseColor(fgColor))
            }
            tv.background = bg
            row.addView(tv)
        }
        parent.addView(row)
    }

    /*
     * 单表复制与纠正仍保留在格式视图的表格卡片中；整页复制始终走全文本。
     */

    private fun copyTable(table: MarkdownTable) {
        val text = tableToPlainText(table)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR table", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun tableToPlainText(table: MarkdownTable): String = buildString {
        table.title?.takeIf { it.isNotBlank() }?.let {
            appendLine(it.trim())
        }
        appendLine(table.headers.joinToString(Char(9).toString()))
        table.rows.forEach { row ->
            appendLine(row.joinToString(Char(9).toString()))
        }
    }.trim()

    private fun correctTable(table: MarkdownTable) {
        val input = EditText(this).apply {
            setText(tableToPlainText(table))
            isSingleLine = false
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 8
            maxLines = 25
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val scroll = ScrollView(this).apply { addView(input) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.result_table_correct_title))
            .setMessage(getString(R.string.result_table_correct_hint))
            .setView(scroll)
            .setPositiveButton(R.string.result_table_correct_ok) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("OCR table corrected", input.text.toString())
                )
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isDarkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun copy() {
        val text = result?.exportText ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR result", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val r = result ?: return
        val options = arrayOf(
            getString(R.string.share_txt),
            getString(R.string.share_md),
            getString(R.string.share_docx)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.share_choice_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareFile(Exporter.buildTxt(r), "text/plain")
                    1 -> shareFile(Exporter.buildMarkdown(r), "text/markdown")
                    else -> shareFile(
                        Exporter.buildDocx(r),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 复制用的纯文本：去掉 HTML / Markdown 格式，保留可读文本和表格内容 */
    private val OcrResult.exportText: String
        get() = PlainText.convert(this).ifBlank { fullText.ifBlank { markdown.orEmpty() } }

    /** 格式视图与格式化导出的统一源：优先使用结构化 Markdown。 */
    private val OcrResult.formattedSource: String?
        get() = markdown?.takeIf { it.isNotBlank() } ?: fullText.ifBlank { null }

    private fun export(mode: String) {
        val r = result ?: return
        val mime = when (mode) {
            "txt" -> "text/plain"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> "text/markdown"
        }
        pendingExportMode = mode
        val name = "OCR_${System.currentTimeMillis()}.$mode"
        runCatching {
            createDoc.launch(name)
        }.onFailure {
            // 极旧设备不支持 CreateDocument 时退回分享
            pendingExportMode = null
            val file = when (mode) {
                "txt" -> Exporter.buildTxt(r)
                "docx" -> Exporter.buildDocx(r)
                else -> Exporter.buildMarkdown(r)
            }
            shareFile(file, mime)
        }
    }

    private fun shareFile(file: File, mime: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
