package com.example.paddleocr

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 按云端模型动态展示并保存 AI Studio optionalPayload。 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var model: String
    private lateinit var payload: JSONObject
    private val switches = mutableMapOf<String, SwitchMaterial>()
    private val sliders = mutableMapOf<String, Slider>()
    private val spinners = mutableMapOf<String, Pair<Spinner, List<String>>>()
    private val auxiliarySwitches = mutableMapOf<String, SwitchMaterial>()

    companion object {
        private const val EXTRA_MODEL = "model"

        fun intent(context: Context, model: String): Intent =
            Intent(context, AdvancedSettingsActivity::class.java)
                .putExtra(EXTRA_MODEL, model)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = intent.getStringExtra(EXTRA_MODEL).orEmpty().ifBlank { Prefs.modelName }
        payload = Prefs.advancedOptions(model) ?: CloudAdvancedOptions.defaultPayload(model)
        setContentView(createContent())
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
        }

        root.addView(MaterialToolbar(this).apply {
            title = "$model 高级设置"
            subtitle = "识别参数配置"
            setTitleTextColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.white))
            setSubtitleTextColor(0xCCFFFFFF.toInt())
            setBackgroundColor(ContextCompat.getColor(this@AdvancedSettingsActivity, R.color.primary))
            navigationIcon = ContextCompat.getDrawable(this@AdvancedSettingsActivity, R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }, matchWrap(height = dp(64)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
        }
        scroll.addView(content, matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        content.addView(infoCard())
        when (CloudAdvancedOptions.familyOf(model)) {
            CloudModelFamily.VL -> buildVlSettings(content)
            CloudModelFamily.OCR -> buildOcrSettings(content)
            CloudModelFamily.STRUCTURE -> buildStructureSettings(content)
            CloudModelFamily.UNSUPPORTED -> content.addView(bodyText("该自定义模型没有已知的高级参数，将继续使用云端默认配置。"))
        }
        content.addView(actionButtons())
        return root
    }

    private fun infoCard(): View = MaterialCardView(this).apply {
        radius = dp(12).toFloat()
        cardElevation = dp(1).toFloat()
        setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer))
        addView(LinearLayout(this@AdvancedSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
            addView(TextView(this@AdvancedSettingsActivity).apply {
                text = modelDescription()
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
            })
            addView(TextView(this@AdvancedSettingsActivity).apply {
                text = if (Prefs.advancedOptions(model) == null) {
                    "当前沿用云端默认参数。调整后点击保存，配置仅对 $model 生效。"
                } else {
                    "已启用自定义参数。自动切换模型时，每个模型会使用各自保存的配置。"
                }
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        })
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) }
    }

    private fun buildVlSettings(content: LinearLayout) {
        addSection(content, "辅助内容解析", "开启后，这类内容会保留在最终 Markdown 中")
        val ignored = payload.optJSONArray("markdownIgnoreLabels").toStringSet()
        CloudAdvancedOptions.auxiliaryLabels.forEach { (key, label) ->
            auxiliarySwitches[key] = addSwitch(content, null, label, key !in ignored)
        }

        addSection(content, "模型参数设置")
        addSwitch(content, "useDocOrientationClassify", "图片方向矫正", false)
        addSwitch(content, "useDocUnwarping", "图片扭曲矫正", false)
        addSwitch(content, "useLayoutDetection", "版面分析", true)
        addSwitch(content, "useChartRecognition", "图表识别", false)
        addSwitch(content, "useSealRecognition", "印章识别", true)
        addSwitch(content, "useOcrForImageBlock", "图片内文字识别", false)
        addSwitch(content, "mergeTables", "跨页表格合并", true)
        addSwitch(content, "relevelTitles", "段落标题级别识别", true)

        addSpinner(
            content,
            "layoutShapeMode",
            "版面检测结果的几何形状",
            listOf("自动", "矩形", "四边形", "多边形"),
            listOf("auto", "rect", "quad", "poly"),
            "auto"
        )
        addSpinner(
            content,
            "promptLabel",
            "Prompt 类型（关闭版面分析时生效）",
            listOf("文本", "公式", "表格", "图表", "印章", "文本检测与识别"),
            listOf("ocr", "formula", "table", "chart", "seal", "spotting"),
            "ocr"
        )
        addSlider(content, "repetitionPenalty", "重复抑制强度", 0.1f, 2f, 0.05f, 1f, 2)
        addSlider(content, "temperature", "识别稳定性（温度）", 0f, 1f, 0.05f, 0f, 2)
    }

    private fun buildOcrSettings(content: LinearLayout) {
        addSection(content, "图像预处理")
        addSwitch(content, "useDocOrientationClassify", "图片方向矫正", true)
        addSwitch(content, "useDocUnwarping", "图片扭曲矫正", true)
        addSwitch(content, "useTextlineOrientation", "文本行方向矫正", true)

        addSection(content, "文字检测与识别", "PP-OCRv6 / v5 专用参数")
        addSpinner(
            content,
            "textDetLimitSideLen",
            "检测图像边长",
            listOf("64 px", "320 px", "640 px", "960 px", "1280 px", "1920 px"),
            listOf("64", "320", "640", "960", "1280", "1920"),
            "64"
        )
        addSpinner(
            content,
            "textDetLimitType",
            "边长限制方式",
            listOf("保证短边不小于设定值", "限制长边不超过设定值"),
            listOf("min", "max"),
            "min"
        )
        addSlider(content, "textDetThresh", "文本像素阈值", 0.05f, 1f, 0.05f, 0.3f, 2)
        addSlider(content, "textDetBoxThresh", "文本框置信度阈值", 0.05f, 1f, 0.05f, 0.6f, 2)
        addSlider(content, "textDetUnclipRatio", "文本框扩张比例", 0.5f, 3f, 0.1f, 1.5f, 1)
        addSlider(content, "textRecScoreThresh", "识别结果置信度阈值", 0f, 1f, 0.05f, 0f, 2)
    }

    private fun buildStructureSettings(content: LinearLayout) {
        addSection(content, "图像预处理")
        addSwitch(content, "useDocOrientationClassify", "图片方向矫正", true)
        addSwitch(content, "useDocUnwarping", "图片扭曲矫正", true)
        addSwitch(content, "useTextlineOrientation", "文本行方向矫正", true)

        addSection(content, "版面结构解析", "PP-StructureV3 专用模块")
        addSwitch(content, "useRegionDetection", "版面区域检测", true)
        addSwitch(content, "useTableRecognition", "表格识别", true)
        addSwitch(content, "useFormulaRecognition", "公式识别", true)
        addSwitch(content, "useChartRecognition", "图表识别", false)
        addSwitch(content, "useSealRecognition", "印章识别", true)
        addSwitch(content, "prettifyMarkdown", "Markdown 排版美化", true)
        addSwitch(content, "showFormulaNumber", "保留公式编号", true)
        addSpinner(
            content,
            "textDetLimitSideLen",
            "检测图像边长",
            listOf("640 px", "960 px", "1280 px", "1920 px"),
            listOf("640", "960", "1280", "1920"),
            "960"
        )
        addSpinner(
            content,
            "textDetLimitType",
            "边长限制方式",
            listOf("限制长边不超过设定值", "保证短边不小于设定值"),
            listOf("max", "min"),
            "max"
        )
        addSlider(content, "textRecScoreThresh", "识别结果置信度阈值", 0f, 1f, 0.05f, 0f, 2)
    }

    private fun addSection(parent: LinearLayout, title: String, subtitle: String? = null) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
        }, matchWrap().apply { topMargin = dp(12) })
        if (subtitle != null) {
            parent.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }, matchWrap().apply { topMargin = dp(3); bottomMargin = dp(6) })
        }
    }

    private fun addSwitch(
        parent: LinearLayout,
        key: String?,
        label: String,
        default: Boolean
    ): SwitchMaterial {
        val control = SwitchMaterial(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            isChecked = key?.let { payload.optBoolean(it, default) } ?: default
        }
        parent.addView(control, matchWrap())
        if (key != null) switches[key] = control
        return control
    }

    private fun addSpinner(
        parent: LinearLayout,
        key: String,
        label: String,
        labels: List<String>,
        values: List<String>,
        default: String
    ) {
        parent.addView(fieldLabel(label), matchWrap().apply { topMargin = dp(12) })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AdvancedSettingsActivity,
                android.R.layout.simple_spinner_item,
                labels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            minimumHeight = dp(48)
            val raw = if (payload.has(key)) payload.opt(key)?.toString() ?: default else default
            setSelection(values.indexOf(raw).coerceAtLeast(0))
        }
        parent.addView(spinner, matchWrap())
        spinners[key] = spinner to values
    }

    private fun addSlider(
        parent: LinearLayout,
        key: String,
        label: String,
        from: Float,
        to: Float,
        step: Float,
        default: Float,
        decimals: Int
    ) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(fieldLabel(label), LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        val valueText = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary))
        }
        header.addView(valueText, LinearLayout.LayoutParams(dp(58), dp(42)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        parent.addView(header, matchWrap().apply { topMargin = dp(8) })

        val slider = Slider(this).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            value = payload.optDouble(key, default.toDouble()).toFloat().coerceIn(from, to)
            setLabelFormatter { formatNumber(it, decimals) }
        }
        valueText.text = formatNumber(slider.value, decimals)
        slider.addOnChangeListener { _, value, _ -> valueText.text = formatNumber(value, decimals) }
        parent.addView(slider, matchWrap().apply { leftMargin = dp(2); rightMargin = dp(2) })
        sliders[key] = slider
    }

    private fun actionButtons(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(24), 0, 0)

        addView(MaterialButton(this@AdvancedSettingsActivity).apply {
            text = "恢复云端默认"
            setOnClickListener {
                Prefs.clearAdvancedOptions(model)
                Toast.makeText(this@AdvancedSettingsActivity, "已恢复 $model 的云端默认参数", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(6) })

        addView(MaterialButton(this@AdvancedSettingsActivity).apply {
            text = "保存高级设置"
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(6) })
    }

    private fun save() {
        if (CloudAdvancedOptions.familyOf(model) == CloudModelFamily.UNSUPPORTED) {
            finish()
            return
        }
        val result = JSONObject()
        switches.forEach { (key, view) -> result.put(key, view.isChecked) }
        sliders.forEach { (key, view) -> result.put(key, view.value.toDouble()) }
        spinners.forEach { (key, pair) ->
            val value = pair.second[pair.first.selectedItemPosition]
            if (key == "textDetLimitSideLen") result.put(key, value.toInt())
            else result.put(key, value)
        }
        if (auxiliarySwitches.isNotEmpty()) {
            val ignored = JSONArray()
            auxiliarySwitches.forEach { (key, view) -> if (!view.isChecked) ignored.put(key) }
            result.put("markdownIgnoreLabels", ignored)
        }
        Prefs.saveAdvancedOptions(model, result)
        Toast.makeText(this, "$model 高级设置已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun modelDescription(): String = when (CloudAdvancedOptions.familyOf(model)) {
        CloudModelFamily.VL -> "视觉语言模型：适合复杂版式、表格、公式、印章与图文混排"
        CloudModelFamily.OCR -> "通用文字识别：适合快速检测和识别纯文字内容"
        CloudModelFamily.STRUCTURE -> "文档结构解析：适合版面、表格、公式与图表还原"
        CloudModelFamily.UNSUPPORTED -> "自定义云端模型"
    }

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(12), 0, dp(12))
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return CloudAdvancedOptions.auxiliaryLabels.keys
        return buildSet {
            for (i in 0 until length()) add(optString(i))
        }
    }

    private fun formatNumber(value: Float, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)

    private fun resolveColor(attr: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
}
