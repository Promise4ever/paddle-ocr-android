package com.example.paddleocr.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.paddleocr.App
import com.example.paddleocr.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * 结果仓库：负责识别结果入库（Room）、输入图片/缩略图落盘、
 * 从 SharedPreferences 一次性迁移旧版历史记录。
 */
object ResultRepository {

    private const val RESULT_DIR = "ocr_results"
    private const val THUMB_SIZE = 256

    fun dao() = AppDatabase.get().historyDao()

    /**
     * 保存一次识别结果；复制输入图片副本与生成缩略图。
     * @param bitmap 识别时实际发送的图片（裁剪后），可为 null（仅展示用）
     * @return 入库后的记录 id
     */
    suspend fun save(result: OcrResult, bitmap: Bitmap?): Long = withContext(Dispatchers.IO) {
        var imgPath = result.sourceImagePath
        var thumbPath: String? = null
        if (bitmap != null) {
            val dir = File(App.context.filesDir, RESULT_DIR).apply { mkdirs() }
            // UUID 避免同毫秒内多次保存时文件名互相覆盖
            val fileId = UUID.randomUUID().toString()
            val imgFile = File(dir, "${fileId}_img.jpg")
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, imgFile.outputStream())
            imgPath = imgFile.absolutePath

            val thumb = Bitmap.createScaledBitmap(
                bitmap,
                minOf(THUMB_SIZE, bitmap.width).coerceAtLeast(1),
                (bitmap.height.toFloat() / bitmap.width * minOf(THUMB_SIZE, bitmap.width))
                    .toInt().coerceAtLeast(1),
                true
            )
            val thumbFile = File(dir, "${fileId}_thumb.jpg")
            thumb.compress(Bitmap.CompressFormat.JPEG, 85, thumbFile.outputStream())
            if (thumb !== bitmap) thumb.recycle()
            thumbPath = thumbFile.absolutePath
        }

        dao().insert(
            HistoryEntity(
                time = System.currentTimeMillis(),
                linesJson = JSONArray().apply { result.lines.forEach { put(it.toJson()) } }.toString(),
                markdown = result.markdown,
                modelName = result.modelName,
                sourceImagePath = imgPath,
                thumbnailPath = thumbPath,
                favorite = false
            )
        )
    }

    /** 把一次结果写入历史（不含图片），返回记录 id */
    suspend fun saveTextOnly(result: OcrResult): Long = withContext(Dispatchers.IO) {
        dao().insert(
            HistoryEntity(
                time = System.currentTimeMillis(),
                linesJson = JSONArray().apply { result.lines.forEach { put(it.toJson()) } }.toString(),
                markdown = result.markdown,
                modelName = result.modelName,
                favorite = false
            )
        )
    }

    fun entityToResult(e: HistoryEntity): OcrResult {
        val lines = runCatching {
            val arr = JSONArray(e.linesJson)
            (0 until arr.length()).mapNotNull {
                arr.optJSONObject(it)?.let(OcrLine::fromJson)
            }
        }.getOrDefault(emptyList())
        return OcrResult(
            lines = lines,
            markdown = e.markdown,
            modelName = e.modelName,
            sourceImagePath = e.sourceImagePath,
            isCloud = false
        )
    }

    suspend fun deleteEntry(id: Long) = withContext(Dispatchers.IO) {
        val e = dao().byId(id)
        dao().delete(id)
        e?.let { entry ->
            listOfNotNull(entry.sourceImagePath, entry.thumbnailPath)
                .filter { it.startsWith(App.context.filesDir.absolutePath) }
                .forEach { runCatching { File(it).delete() } }
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao().clear()
        val dir = File(App.context.filesDir, RESULT_DIR)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    /**
     * 旧版 SharedPreferences 历史（KEY_HISTORY JSON 数组）一次性迁移到 Room。
     * 在 MainActivity 启动时调用；幂等（迁移后打标记）。
     */
    suspend fun migrateLegacyHistoryIfNeeded() = withContext(Dispatchers.IO) {
        if (Prefs.legacyHistoryMigrated) return@withContext
        val raw = Prefs.legacyHistoryRaw()
        if (!raw.isNullOrBlank()) {
            val arr = runCatching { JSONArray(raw) }.getOrNull()
            if (arr != null) {
                val dao = dao()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val time = o.optLong("time", 0L)
                    val lines = o.optJSONArray("lines") ?: continue
                    val linesJson = JSONArray().apply {
                        for (j in 0 until lines.length()) {
                            val line = lines.optJSONObject(j) ?: continue
                            put(OcrLine.fromJson(line).toJson())
                        }
                    }.toString()
                    dao.insert(
                        HistoryEntity(
                            time = time,
                            linesJson = linesJson,
                            modelName = null,
                            favorite = false
                        )
                    )
                }
            }
        }
        Prefs.legacyHistoryMigrated = true
        Prefs.clearLegacyHistory()
    }

    /** 导出/分享时的临时目录 */
    fun cacheFile(name: String): File = File(App.context.cacheDir, name)
}
