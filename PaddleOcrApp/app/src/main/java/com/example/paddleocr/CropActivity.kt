package com.example.paddleocr

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.paddleocr.databinding.ActivityCropBinding
import kotlinx.coroutines.launch

class CropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCropBinding
    private lateinit var viewModel: CropViewModel
    private var dialog: AlertDialog? = null

    companion object {
        private const val EXTRA_URI = "extra_uri"

        fun start(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, CropActivity::class.java)
                    .putExtra(EXTRA_URI, uri.toString())
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.crop_title)

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString == null) {
            finish()
            return
        }

        viewModel = ViewModelProvider(this, CropViewModel.Factory(uriString))[CropViewModel::class.java]

        binding.selector.onSelectionChanged = { viewModel.updatePreview(it) { preview ->
            binding.preview.setImageBitmap(preview)
        } }
        binding.btnReset.setOnClickListener { binding.selector.resetSelection() }
        binding.btnStartOcr.setOnClickListener { cropAndRecognize() }
        binding.btnCancelOcr.setOnClickListener { viewModel.cancelOcr() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s -> render(s) }
            }
        }

        viewModel.loadImages(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun render(s: CropUiState) {
        binding.progressOverlay.isVisible = s.loading
        binding.btnCancelOcr.isVisible = s.ocrRunning
        if (s.loadError != null) {
            toast(getString(R.string.toast_load_failed, s.loadError))
            finish()
            return
        }
        if (s.loading) return

        val bmp = viewModel.displayBitmap()
        if (bmp != null && binding.selector.bitmap !== bmp) {
            binding.selector.bitmap = bmp
            binding.selector.resetSelection()
        }

        if (s.ocrRunning) {
            if (dialog == null || dialog?.isShowing != true) {
                val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
                dialog = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ocr_progress_title))
                    .setView(dialogView)
                    .setCancelable(false)
                    .setNegativeButton(getString(R.string.btn_cancel_ocr), null)
                    .create()
                dialog?.setOnShowListener {
                    dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)
                        ?.setOnClickListener { viewModel.cancelOcr() }
                }
                dialog?.show()
            }
            dialog?.findViewById<TextView>(R.id.progressMsg)?.text = s.progressMsg
        } else {
            dialog?.dismiss()
            dialog = null
        }
    }

    private fun cropAndRecognize() {
        viewModel.cropAndRecognize(
            binding.selector.getNormalizedSelection(),
            onSuccess = { id ->
                ResultActivity.start(this, id)
            },
            onError = { msg ->
                if (msg == "已取消") {
                    toast(getString(R.string.toast_ocr_cancelled))
                } else {
                    toast(getString(R.string.toast_ocr_failed, msg))
                }
            }
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
