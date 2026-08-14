package com.example.paddleocr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.paddleocr.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var cameraUri: Uri? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                cameraUri?.let { CropActivity.start(this, it) }
            } else {
                toast(getString(R.string.toast_camera_cancel))
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) CropActivity.start(this, uri) else toast(getString(R.string.toast_no_image))
        }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) CropActivity.start(this, uri) else toast(getString(R.string.toast_no_image))
        }

    private val pickBatch =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) BatchActivity.start(this, uris)
            else toast(getString(R.string.toast_no_image))
        }

    private val getBatchContent =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) BatchActivity.start(this, uris)
            else toast(getString(R.string.toast_no_image))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_CAMERA_URI)?.let { cameraUri = Uri.parse(it) }
        }

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnGallery.setOnClickListener { launchGallery() }
        binding.btnBatch.setOnClickListener { launchBatch() }
        binding.btnQuotaRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnOpenLast.setOnClickListener {
            viewModel.state.value.lastResultId?.let { ResultActivity.start(this, it) }
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            BottomNav.onTabSelected(this, item.itemId)
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_ocr

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s -> render(s) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 拍照过程中旋转/重建后仍能找到照片文件
        cameraUri?.toString()?.let { outState.putString(KEY_CAMERA_URI, it) }
    }

    private fun render(s: MainUiState) {
        val isCloud = s.isCloud
        binding.quotaCard.visibility = if (isCloud) View.VISIBLE else View.GONE
        if (isCloud) {
            binding.quotaModel.text = getString(R.string.quota_model, s.modelName)
            binding.quotaDetail.text = getString(
                R.string.quota_detail, s.used, s.remaining, s.limit
            )
            binding.quotaExhausted.visibility =
                if (s.exhausted) View.VISIBLE else View.GONE
        }
        binding.offlineBadge.visibility =
            if (Prefs.mode == ApiMode.OFFLINE) View.VISIBLE else View.GONE

        if (s.lastResultId == null) {
            binding.lastResultCard.visibility = View.GONE
        } else {
            binding.lastResultCard.visibility = View.VISIBLE
            binding.lastResultText.text = s.lastResultPreview
            val label = if (s.lastResultTime > 0) {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.lastResultTime))
            } else ""
            binding.lastResultTime.text = getString(R.string.last_result_time, label)
        }
    }

    private fun launchCamera() {
        // 固定文件名 + 先清理旧文件，避免 cacheDir 堆积
        val file = File(cacheDir, "ocr_capture.jpg")
        file.delete()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast(getString(R.string.toast_camera_failed, e.message ?: ""))
        }
    }

    private fun launchGallery() {
        try {
            pickImage.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        } catch (e: Exception) {
            getContent.launch("image/*")
        }
    }

    private fun launchBatch() {
        try {
            pickBatch.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        } catch (e: Exception) {
            getBatchContent.launch("image/*")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_CAMERA_URI = "key_camera_uri"
    }
}
