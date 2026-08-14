package com.example.paddleocr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.paddleocr.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var timeoutOptions: List<Int> = TIMEOUT_OPTIONS
    private var modelOptions: List<String> = Prefs.CLOUD_MODELS

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)

        private val TIMEOUT_OPTIONS = listOf(30, 60, 120, 180)
        private val RETRY_OPTIONS = listOf(0, 1, 2, 3)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ---- 模式 ----
        val modes = ApiMode.entries.map { it.label }
        binding.modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = ApiMode.entries[position]
                val current = binding.urlEdit.text?.toString()?.trim().orEmpty()
                // URL 跟随模式自动切换默认值
                if (mode == ApiMode.AI_STUDIO) {
                    if (current.isBlank() || current == Prefs.LAN_DEFAULT_URL) {
                        binding.urlEdit.setText(Prefs.AI_STUDIO_DEFAULT_URL)
                    }
                } else if (current == Prefs.AI_STUDIO_DEFAULT_URL && mode != ApiMode.AI_STUDIO) {
                    binding.urlEdit.setText(Prefs.LAN_DEFAULT_URL)
                }
                applyModeUi(mode)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // 先挂 listener 再 setSelection，保证初始状态按当前模式正确显隐
        binding.modeSpinner.setSelection(ApiMode.entries.indexOfFirst { it == Prefs.mode })

        // ---- 模型（AI Studio） ----
        modelOptions =
            if (Prefs.CLOUD_MODELS.contains(Prefs.modelName)) Prefs.CLOUD_MODELS
            else listOf(Prefs.modelName) + Prefs.CLOUD_MODELS
        binding.modelSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, modelOptions).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        binding.modelSpinner.setSelection(
            modelOptions.indexOf(Prefs.modelName).coerceAtLeast(0)
        )

        // ---- PaddleX fileType ----
        val fileTypes = listOf(
            "1（图片，PP-OCRv5 新版服务）",
            "0（图片，PaddleX 3.0/3.1 旧版服务）"
        )
        binding.fileTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fileTypes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.fileTypeSpinner.setSelection(if (Prefs.paddlexFileType == 1) 0 else 1)

        // ---- 超时 / 重试 ----
        timeoutOptions =
            if (TIMEOUT_OPTIONS.contains(Prefs.timeoutReadSec)) TIMEOUT_OPTIONS
            else (TIMEOUT_OPTIONS + Prefs.timeoutReadSec).sorted()
        binding.timeoutSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, timeoutOptions.map { "${it} 秒" }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        val tIdx = timeoutOptions.indexOf(Prefs.timeoutReadSec).coerceAtLeast(0)
        binding.timeoutSpinner.setSelection(tIdx)

        binding.retrySpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, RETRY_OPTIONS.map { "$it 次" }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        binding.retrySpinner.setSelection(RETRY_OPTIONS.indexOf(Prefs.retryCount).coerceAtLeast(0))

        binding.autoSwitchCheck.isChecked = Prefs.autoSwitchModel

        // ---- 回填 ----
        binding.urlEdit.setText(Prefs.serverUrl)
        binding.tokenEdit.setText(Prefs.token)
        binding.hubImageCheck.isChecked = Prefs.hubImageField

        // http 明文警告随输入实时更新
        binding.urlEdit.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val mode = ApiMode.entries[binding.modeSpinner.selectedItemPosition]
                    if (mode != ApiMode.OFFLINE) {
                        binding.httpWarning.isVisible =
                            s?.toString()?.trim()?.startsWith("http://") == true
                    }
                }
            }
        )

        binding.btnSave.setOnClickListener { save() }

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_help) {
                startActivity(HelpActivity.intent(this))
                true
            } else {
                false
            }
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            BottomNav.onTabSelected(this, item.itemId)
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_settings
    }

    private fun applyModeUi(mode: ApiMode) {
        val isOffline = mode == ApiMode.OFFLINE
        val isAiStudio = mode == ApiMode.AI_STUDIO
        val isPaddlex = mode == ApiMode.PADDLEX
        val isHub = mode == ApiMode.HUB
        val isNetwork = !isOffline

        // token 是账号凭据，与当前模式无关：离线模式下也保留显示，避免用户以为配置丢失
        binding.serverGroup.isVisible = true
        binding.urlInput.isVisible = isNetwork
        binding.modelGroup.isVisible = isAiStudio
        binding.fileTypeGroup.isVisible = isPaddlex
        binding.hubImageCheck.isVisible = isHub
        binding.networkGroup.isVisible = isNetwork
        binding.autoSwitchCheck.isVisible = isAiStudio
        binding.offlineCard.isVisible = isOffline

        // http 明文警告
        val url = binding.urlEdit.text?.toString()?.trim().orEmpty()
        binding.httpWarning.isVisible =
            isNetwork && url.startsWith("http://")
    }

    private fun save() {
        val mode = ApiMode.entries[binding.modeSpinner.selectedItemPosition]
        val url = binding.urlEdit.text?.toString()?.trim().orEmpty()
        if (mode != ApiMode.OFFLINE) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(this, R.string.settings_url_invalid, Toast.LENGTH_SHORT).show()
                return
            }
            if (url.length <= "http://".length) {
                Toast.makeText(this, R.string.settings_url_invalid, Toast.LENGTH_SHORT).show()
                return
            }
        }
        Prefs.mode = mode
        // token 与模式无关，始终保存，避免切换离线模式后凭据被静默丢弃
        Prefs.token = binding.tokenEdit.text?.toString().orEmpty()
        if (mode != ApiMode.OFFLINE) {
            Prefs.serverUrl = url
            Prefs.hubImageField = binding.hubImageCheck.isChecked
            Prefs.timeoutReadSec = timeoutOptions[binding.timeoutSpinner.selectedItemPosition]
            Prefs.retryCount = RETRY_OPTIONS[binding.retrySpinner.selectedItemPosition]
            if (mode == ApiMode.AI_STUDIO) {
                Prefs.modelName = modelOptions[binding.modelSpinner.selectedItemPosition]
            }
            if (mode == ApiMode.PADDLEX) {
                Prefs.paddlexFileType = if (binding.fileTypeSpinner.selectedItemPosition == 0) 1 else 0
            }
        }
        Prefs.autoSwitchModel = binding.autoSwitchCheck.isChecked
        // 重建 HTTP 客户端，让超时/重试设置立即生效
        OcrClient.rebuildClient()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        // 保存后留在本页，便于确认设置已生效；用户可自行返回或切换底部导航
    }
}
