package com.example.paddleocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.paddleocr.data.HistoryEntity
import com.example.paddleocr.data.OcrResult
import com.example.paddleocr.data.ResultRepository
import com.example.paddleocr.databinding.ActivityResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
        val hasMarkdown = !r.markdown.isNullOrBlank()
        val hasPolygon = r.lines.any { it.polygon != null }

        // Markdown 结构化展示（WebView 独立区域、内部滚动），否则纯文本
        if (hasMarkdown) {
            binding.resultScroll.visibility = View.GONE
            binding.resultWeb.visibility = View.VISIBLE
            val dark = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            binding.resultWeb.loadDataWithBaseURL(
                null,
                MdToHtml.convert(r.markdown!!, dark),
                "text/html",
                "utf-8",
                null
            )
        } else {
            binding.resultWeb.visibility = View.GONE
            binding.resultScroll.visibility = View.VISIBLE
            binding.resultText.text = r.fullText
        }

        if (r.lines.isEmpty()) {
            binding.stats.text = getString(R.string.empty_result)
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

    private fun copy() {
        val text = result?.exportText ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR result", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = result?.exportText ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }

    /** 复制/分享用的纯文本：优先逐行文本，仅 Markdown 结果时回退到原文 */
    private val OcrResult.exportText: String
        get() = fullText.ifBlank { markdown.orEmpty() }

    private fun export(mode: String) {
        val r = result ?: return
        val mime = if (mode == "txt") "text/plain" else "text/markdown"
        pendingExportMode = mode
        val name = "OCR_${System.currentTimeMillis()}.$mode"
        runCatching {
            createDoc.launch(name)
        }.onFailure {
            // 极旧设备不支持 CreateDocument 时退回分享
            pendingExportMode = null
            val file = if (mode == "txt") Exporter.buildTxt(r) else Exporter.buildMarkdown(r)
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
