package com.example.paddleocr

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.paddleocr.data.ResultRepository
import com.example.paddleocr.databinding.ActivityOverlayBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 识别框叠加页：展示检测到的每个文字区域在原图中的位置。
 */
class OverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOverlayBinding

    companion object {
        private const val EXTRA_ID = "extra_id"

        fun start(context: Context, id: Long) {
            context.startActivity(
                Intent(context, OverlayActivity::class.java).putExtra(EXTRA_ID, id)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.overlay_title)

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0) {
            finish()
            return
        }

        binding.progress.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val entity = ResultRepository.dao().byId(id)
            val bitmap = entity?.sourceImagePath?.let { path ->
                val f = File(path)
                if (f.exists()) BitmapFactory.decodeFile(path) else null
            }
            withContext(Dispatchers.Main) {
                binding.progress.visibility = android.view.View.GONE
                if (entity == null || bitmap == null) {
                    Toast.makeText(this@OverlayActivity, R.string.overlay_unavailable, Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                val res = ResultRepository.entityToResult(entity)
                binding.stats.text = getString(
                    R.string.overlay_stats,
                    res.lines.count { it.polygon != null }
                )
                binding.overlay.setData(bitmap, res)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
