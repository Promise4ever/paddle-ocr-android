package com.example.paddleocr

import android.content.Context
import com.example.paddleocr.security.TokenCipher
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

enum class ApiMode(val label: String) {
    OFFLINE("端侧离线识别（内置 PP-OCRv4）"),
    PADDLEX("PaddleX 服务（PP-OCRv5/v6）"),
    HUB("PaddleHub Serving（PP-OCRv3）"),
    AI_STUDIO("AI Studio 云端 API")
}

object Prefs {
    private const val FILE = "paddle_ocr_settings"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_MODE = "api_mode"
    private const val KEY_TOKEN = "token"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_FILETYPE = "paddlex_file_type"
    private const val KEY_HUB_IMAGE = "hub_image_field"
    private const val KEY_HISTORY = "history"
    private const val KEY_QUOTA_USAGE = "quota_usage"
    private const val KEY_QUOTA_EXHAUSTED = "quota_exhausted"
    private const val KEY_QUOTA_LIMIT = "quota_limit"
    private const val KEY_TIMEOUT_CONNECT = "timeout_connect_sec"
    private const val KEY_TIMEOUT_READ = "timeout_read_sec"
    private const val KEY_RETRY = "retry_count"
    private const val KEY_AUTO_SWITCH = "auto_switch_model"
    private const val KEY_LEGACY_MIGRATED = "legacy_history_migrated"

    const val LAN_DEFAULT_URL = "http://192.168.1.100:8080/ocr/predict"
    const val AI_STUDIO_DEFAULT_URL = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs"
    const val DEFAULT_QUOTA_LIMIT = 3000
    const val HISTORY_MAX = 50

    /** 云端自动切换候选模型（429 时依次尝试，跳过当前模型） */
    val CLOUD_MODELS = listOf(
        "PaddleOCR-VL-1.6",
        "PaddleOCR-VL-1.5",
        "PaddleOCR-VL",
        "PP-OCRv6",
        "PP-OCRv5",
        "PP-StructureV3"
    )

    private val sp
        get() = App.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_SERVER_URL, LAN_DEFAULT_URL) ?: LAN_DEFAULT_URL
        set(value) {
            sp.edit().putString(KEY_SERVER_URL, value.trim()).apply()
        }

    var mode: ApiMode
        get() = runCatching {
            ApiMode.valueOf(sp.getString(KEY_MODE, ApiMode.PADDLEX.name) ?: ApiMode.PADDLEX.name)
        }.getOrDefault(ApiMode.PADDLEX)
        set(value) {
            sp.edit().putString(KEY_MODE, value.name).apply()
        }

    /** Access Token 密文存储（Android Keystore AES-GCM），旧明文自动兼容 */
    var token: String
        get() = TokenCipher.decrypt(sp.getString(KEY_TOKEN, "") ?: "")
        set(value) {
            sp.edit().putString(KEY_TOKEN, TokenCipher.encrypt(value.trim())).apply()
        }

    var modelName: String
        get() = sp.getString(KEY_MODEL_NAME, "PaddleOCR-VL-1.6") ?: "PaddleOCR-VL-1.6"
        set(value) {
            sp.edit().putString(KEY_MODEL_NAME, value.trim()).apply()
        }

    var paddlexFileType: Int
        get() = sp.getInt(KEY_FILETYPE, 1)
        set(value) {
            sp.edit().putInt(KEY_FILETYPE, value).apply()
        }

    var hubImageField: Boolean
        get() = sp.getBoolean(KEY_HUB_IMAGE, false)
        set(value) {
            sp.edit().putBoolean(KEY_HUB_IMAGE, value).apply()
        }

    // ---- 网络与重试（可配置） ----

    var timeoutConnectSec: Int
        get() = sp.getInt(KEY_TIMEOUT_CONNECT, 15)
        set(value) {
            sp.edit().putInt(KEY_TIMEOUT_CONNECT, value.coerceIn(5, 120)).apply()
        }

    var timeoutReadSec: Int
        get() = sp.getInt(KEY_TIMEOUT_READ, 60)
        set(value) {
            sp.edit().putInt(KEY_TIMEOUT_READ, value.coerceIn(15, 300)).apply()
        }

    var retryCount: Int
        get() = sp.getInt(KEY_RETRY, 2)
        set(value) {
            sp.edit().putInt(KEY_RETRY, value.coerceIn(0, 5)).apply()
        }

    /** 云端 429 时自动切换候选模型重试 */
    var autoSwitchModel: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH, true)
        set(value) {
            sp.edit().putBoolean(KEY_AUTO_SWITCH, value).apply()
        }

    // ---- 旧版历史迁移 ----

    var legacyHistoryMigrated: Boolean
        get() = sp.getBoolean(KEY_LEGACY_MIGRATED, false)
        set(value) {
            sp.edit().putBoolean(KEY_LEGACY_MIGRATED, value).apply()
        }

    /** 旧版 SharedPreferences 历史原始 JSON（迁移用，迁移后清空） */
    fun legacyHistoryRaw(): String? = sp.getString(KEY_HISTORY, null)

    fun clearLegacyHistory() {
        sp.edit().remove(KEY_HISTORY).apply()
    }

    // ---- 云端配额（每模型每日上限，官方规则 3000 页；按本机统计估算剩余）----

    var quotaLimit: Int
        get() = sp.getInt(KEY_QUOTA_LIMIT, DEFAULT_QUOTA_LIMIT)
        set(value) {
            sp.edit().putInt(KEY_QUOTA_LIMIT, value.coerceAtLeast(1)).apply()
        }

    private fun quotaKey(model: String): String {
        val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        return "$date|${model.trim()}"
    }

    fun quotaUsedToday(model: String): Int {
        val raw = sp.getString(KEY_QUOTA_USAGE, "{}") ?: "{}"
        return runCatching { JSONObject(raw).optInt(quotaKey(model), 0) }.getOrDefault(0)
    }

    fun quotaRemaining(model: String): Int =
        (quotaLimit - quotaUsedToday(model)).coerceAtLeast(0)

    fun addQuotaUsage(model: String, pages: Int) {
        if (pages <= 0) return
        val key = quotaKey(model)
        val obj = runCatching { JSONObject(sp.getString(KEY_QUOTA_USAGE, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        obj.put(key, obj.optInt(key, 0) + pages)
        // 只保留今天的记录，避免长期堆积
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val cleaned = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith(today)) cleaned.put(k, obj.optInt(k, 0))
        }
        sp.edit().putString(KEY_QUOTA_USAGE, cleaned.toString()).apply()
        setQuotaExhausted(model, false)
    }

    fun isQuotaExhausted(model: String): Boolean {
        val raw = sp.getString(KEY_QUOTA_EXHAUSTED, "{}") ?: "{}"
        return runCatching { JSONObject(raw).optBoolean(quotaKey(model), false) }.getOrDefault(false)
    }

    fun setQuotaExhausted(model: String, exhausted: Boolean) {
        val key = quotaKey(model)
        val obj = runCatching { JSONObject(sp.getString(KEY_QUOTA_EXHAUSTED, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        if (exhausted) {
            obj.put(key, true)
        } else {
            obj.remove(key)
        }
        sp.edit().putString(KEY_QUOTA_EXHAUSTED, obj.toString()).apply()
    }

    /** 清理所有配额状态（切换账号/次日恢复按钮用） */
    fun resetQuotaState() {
        sp.edit()
            .remove(KEY_QUOTA_USAGE)
            .remove(KEY_QUOTA_EXHAUSTED)
            .apply()
    }
}
