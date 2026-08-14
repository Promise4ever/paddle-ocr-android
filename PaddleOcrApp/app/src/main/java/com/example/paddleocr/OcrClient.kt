package com.example.paddleocr

import android.graphics.Bitmap
import android.util.Base64
import com.example.paddleocr.data.OcrLine
import com.example.paddleocr.data.OcrResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 云端 429 额度用尽标记。
 */
class QuotaExceededException(message: String) : IOException(message)

/**
 * PaddleOCR 客户端，兼容四类来源：
 * 0. 端侧离线识别（内置 ONNX 模型，见 OfflineOcrEngine）
 * 1. PaddleX v3 基础服务（PP-OCRv5/v6）：POST /ocr/predict
 *    {"file": base64, "fileType": 1, "modelName": "OCR"}
 * 2. PaddleHub Serving（PP-OCRv3）：POST /predict/ocr_system
 *    {"images": [base64]} 或旧版 {"image": base64}
 * 3. AI Studio 云端任务 API：POST {jobs}/，multipart 上传图片
 *    获得 jobId 后轮询状态，完成后下载 JSONL 结果；
 *    429 时可按设置自动切换候选模型重试。
 *
 * 单例：复用连接池；超时与重试次数可在设置中调整。
 */
object OcrClient {

    private const val POLL_INTERVAL_MS = 4_000L
    private const val POLL_TIMEOUT_MS = 180_000L

    /** 简单重试：网络异常与 5xx 退避重试；429/4xx 不重试 */
    private class RetryInterceptor(private val maxRetries: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var attempt = 0
            while (true) {
                try {
                    val resp = chain.proceed(chain.request())
                    if (resp.code >= 500 && attempt < maxRetries) {
                        resp.close()
                        attempt++
                        Thread.sleep(1_000L * attempt)
                        continue
                    }
                    return resp
                } catch (e: IOException) {
                    if (attempt >= maxRetries) throw e
                    attempt++
                    Thread.sleep(1_000L * attempt)
                }
            }
        }
    }

    private val clientLock = Any()

    @Volatile
    private var client: OkHttpClient? = null

    /** 设置变更后调用，让下一个请求按新配置重建连接池（超时/重试立即生效） */
    fun rebuildClient() {
        synchronized(clientLock) { client = null }
    }

    private fun client(): OkHttpClient {
        client?.let { return it }
        synchronized(clientLock) {
            return client ?: OkHttpClient.Builder()
                .connectTimeout(Prefs.timeoutConnectSec.toLong(), TimeUnit.SECONDS)
                .readTimeout(Prefs.timeoutReadSec.toLong(), TimeUnit.SECONDS)
                .writeTimeout(Prefs.timeoutReadSec.toLong(), TimeUnit.SECONDS)
                .addInterceptor(RetryInterceptor(Prefs.retryCount))
                .build()
                .also { client = it }
        }
    }

    /**
     * @param onProgress 进度回调（主线程安全）
     * @param onModelUsed 云端实际使用的模型（自动切换后可能与设置不同）
     */
    suspend fun recognize(
        bitmap: Bitmap,
        onProgress: ((String) -> Unit)? = null,
        onModelUsed: ((String) -> Unit)? = null
    ): OcrResult = withContext(Dispatchers.IO) {
        when (Prefs.mode) {
            ApiMode.OFFLINE -> OfflineOcrEngine.recognize(bitmap)
            ApiMode.PADDLEX, ApiMode.HUB -> recognizeJson(bitmap)
            ApiMode.AI_STUDIO -> recognizeAiStudio(bitmap, onProgress, onModelUsed)
        }
    }

    private fun jpegBytes(bitmap: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, baos)
        return baos.toByteArray()
    }

    // ------------------------------------------------------------------
    // PaddleX / PaddleHub 同步 JSON 接口
    // ------------------------------------------------------------------

    private fun recognizeJson(bitmap: Bitmap): OcrResult {
        val base64Image = Base64.encodeToString(jpegBytes(bitmap), Base64.NO_WRAP)
        val json = when (Prefs.mode) {
            ApiMode.PADDLEX -> JSONObject()
                .put("file", base64Image)
                .put("fileType", Prefs.paddlexFileType)
                .put("modelName", "OCR")
                .toString()

            ApiMode.HUB -> if (Prefs.hubImageField) {
                JSONObject().put("image", base64Image).toString()
            } else {
                JSONObject().put("images", JSONArray().put(base64Image)).toString()
            }

            else -> error("unreachable")
        }

        val builder = Request.Builder()
            .url(Prefs.serverUrl)
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))

        val body = client().newCall(builder.build()).execute().use { response ->
            val b = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}：${b.take(200)}")
            }
            b
        }
        return parseResponse(body, bitmap.width, bitmap.height)
    }

    /**
     * 解析 PaddleX / PaddleHub 响应，兼容多级结构：
     * - PaddleX v3: result.ocrResults[i].prunedResult.{rec_texts,rec_scores,rec_polys}
     * - 旧版 PaddleX: result.res.{rec_texts,...}
     * - PaddleHub: results[0].data[{text,confidence,text_region}]
     * 全部结果收集（不提前返回），错误字段（errorMsg / status / msg）优先上报。
     */
    private fun parseResponse(body: String, imgW: Int, imgH: Int): OcrResult {
        val root = JSONObject(body)

        // 错误上报：PaddleX errorMsg / PaddleHub status+msg
        root.optString("errorMsg").takeIf { it.isNotBlank() && it != "Success" }?.let {
            throw IOException(it)
        }
        val hubStatus = root.optString("status")
        if (hubStatus.isNotBlank() && hubStatus != "000") {
            val msg = root.optString("msg")
            throw IOException("PaddleHub 错误（$hubStatus）${msg.takeIf { it.isNotBlank() } ?: ""}".trim())
        }

        val out = mutableListOf<OcrLine>()
        var markdown: String? = null
        val result = root.optJSONObject("result")
        if (result != null) {
            result.optJSONArray("ocrResults")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val pruned = item.optJSONObject("prunedResult") ?: item
                    collectRecTexts(pruned, imgW, imgH, out)
                    collectMarkdown(item, result)?.let { markdown = it }
                }
            }
            // 旧版 PaddleX：result.res.{rec_texts, rec_scores, rec_polys}
            result.optJSONObject("res")?.let { res ->
                collectRecTexts(res, imgW, imgH, out)
            }
            if (out.isEmpty()) collectRecTexts(result, imgW, imgH, out)
        }

        // PaddleHub Serving：results[0].data[{text, confidence, text_region}]
        if (out.isEmpty()) {
            root.optJSONArray("results")?.let { results ->
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    item.optJSONArray("data")?.let { data ->
                        for (j in 0 until data.length()) {
                            val d = data.optJSONObject(j) ?: continue
                            val text = d.optString("text").ifBlank { d.optString("rec_texts") }
                            if (text.isNotBlank()) {
                                val region = d.optJSONArray("text_region")?.let { reg ->
                                    if (reg.length() >= 4) {
                                        val pts = mutableListOf<Float>()
                                        for (k in 0 until 4) {
                                            val p = reg.optJSONArray(k) ?: break
                                            if (p.length() >= 2) {
                                                pts.add(p.optDouble(0).toFloat())
                                                pts.add(p.optDouble(1).toFloat())
                                            }
                                        }
                                        pts.takeIf { it.size == 8 }
                                    } else null
                                }
                                out.add(
                                    OcrLine(
                                        text,
                                        d.optDouble("confidence", 0.0),
                                        normalizePolygon(region, imgW, imgH)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        if (out.isEmpty()) collectRecTexts(root, imgW, imgH, out)
        if (out.isEmpty()) throw IOException("未能从响应中解析出文字结果")
        return OcrResult(
            lines = out,
            markdown = markdown,
            imageWidth = imgW,
            imageHeight = imgH,
            isCloud = false
        )
    }

    private fun collectMarkdown(item: JSONObject, root: JSONObject): String? {
        // 收集层级：item.prunedResult.markdown → item.markdown → root.markdown
        val candidates = listOf(
            item.optJSONObject("prunedResult")?.optJSONObject("markdown"),
            item.optJSONObject("markdown"),
            root.optJSONObject("markdown")
        )
        val text = candidates
            .mapNotNull { it?.optString("text") }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return text.ifBlank { null }
    }

    /** 提取 rec_texts/rec_scores/rec_polys；polygon 归一化到 0..1 */
    private fun collectRecTexts(
        o: JSONObject,
        imgW: Int,
        imgH: Int,
        out: MutableList<OcrLine>
    ) {
        val texts = o.optJSONArray("rec_texts") ?: o.optJSONArray("texts") ?: return
        val scores = o.optJSONArray("rec_scores") ?: o.optJSONArray("scores")
        val polys = o.optJSONArray("rec_polys")
        for (i in 0 until texts.length()) {
            val t = texts.optString(i)
            if (t.isBlank()) continue
            var poly: List<Float>? = null
            if (polys != null && i < polys.length()) {
                val p = polys.optJSONArray(i)
                if (p != null) {
                    val pts = mutableListOf<Float>()
                    for (k in 0 until p.length()) {
                        val pt = p.optJSONArray(k) ?: break
                        if (pt.length() >= 2) {
                            pts.add(pt.optDouble(0).toFloat())
                            pts.add(pt.optDouble(1).toFloat())
                        }
                    }
                    poly = normalizePolygon(pts, imgW, imgH)
                }
            }
            out.add(
                OcrLine(
                    t,
                    if (scores != null && i < scores.length()) scores.optDouble(i) else 0.0,
                    poly
                )
            )
        }
    }

    private fun normalizePolygon(pts: List<Float>?, imgW: Int, imgH: Int): List<Float>? {
        if (pts == null || pts.size != 8 || imgW <= 0 || imgH <= 0) return null
        return pts.mapIndexed { idx, v ->
            if (idx % 2 == 0) (v / imgW).coerceIn(0f, 1f) else (v / imgH).coerceIn(0f, 1f)
        }
    }

    // ------------------------------------------------------------------
    // AI Studio 异步任务接口（支持取消与 429 自动切换模型）
    // ------------------------------------------------------------------

    private suspend fun recognizeAiStudio(
        bitmap: Bitmap,
        onProgress: ((String) -> Unit)?,
        onModelUsed: ((String) -> Unit)?
    ): OcrResult {
        val models = mutableListOf(Prefs.modelName)
        if (Prefs.autoSwitchModel) {
            models += Prefs.CLOUD_MODELS.filter { it != Prefs.modelName }
        }

        var lastQuota: QuotaExceededException? = null
        for ((i, model) in models.withIndex()) {
            if (i > 0) onProgress?.invoke("当前模型额度已用完，正在切换 $model …")
            onModelUsed?.invoke(model)
            try {
                return recognizeAiStudioOnce(bitmap, model, onProgress)
            } catch (e: QuotaExceededException) {
                lastQuota = e
                Prefs.setQuotaExhausted(model, true)
                if (!Prefs.autoSwitchModel || i == models.size - 1) throw e
            }
        }
        throw lastQuota ?: IOException("识别失败")
    }

    private suspend fun recognizeAiStudioOnce(
        bitmap: Bitmap,
        model: String,
        onProgress: ((String) -> Unit)?
    ): OcrResult {
        val jobsUrl = Prefs.serverUrl.trimEnd('/')
        val auth = "bearer ${Prefs.token}"

        onProgress?.invoke("正在上传图片…")
        // 该接口对 optionalPayload 校验严格：空对象 {} 使用服务端默认参数
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("optionalPayload", "{}")
            .addFormDataPart(
                "file",
                "ocr_crop.jpg",
                jpegBytes(bitmap).toRequestBody("image/jpeg".toMediaType())
            )
            .build()

        val jobId = client().newCall(
            Request.Builder()
                .url(jobsUrl)
                .header("Authorization", auth)
                .post(multipart)
                .build()
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 429) throw QuotaExceededException("模型 $model 今日额度已用完（429）")
                throw IOException("提交任务失败 HTTP ${resp.code}：${text.take(200)}")
            }
            val data = JSONObject(text).optJSONObject("data")
                ?: throw IOException("提交任务响应异常：${text.take(200)}")
            val id = data.optString("jobId")
            if (id.isBlank()) throw IOException("提交任务响应缺少 jobId：${text.take(200)}")
            id
        }

        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var jsonlUrl: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) {
                throw IOException("识别超时，请稍后重试（云端任务可能仍在计费，超时即视为放弃）")
            }
            val text = client().newCall(
                Request.Builder()
                    .url("$jobsUrl/$jobId")
                    .header("Authorization", auth)
                    .get()
                    .build()
            ).execute().use { resp ->
                val t = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    if (resp.code == 429) throw QuotaExceededException("模型 $model 今日额度已用完（429）")
                    throw IOException("查询任务状态失败 HTTP ${resp.code}：${t.take(200)}")
                }
                t
            }
            val data = JSONObject(text).optJSONObject("data")
                ?: throw IOException("任务状态响应异常：${text.take(200)}")
            when (data.optString("state")) {
                "pending" -> onProgress?.invoke("排队中…")
                "running" -> {
                    val progress = data.optJSONObject("extractProgress")
                    val total = progress?.optInt("totalPages", 0) ?: 0
                    val extracted = progress?.optInt("extractedPages", 0) ?: 0
                    onProgress?.invoke(
                        if (total > 0) "识别中…（$extracted/$total 页）" else "识别中…"
                    )
                }
                "done" -> {
                    val progress = data.optJSONObject("extractProgress")
                    val pages = progress?.optInt("extractedPages", 1) ?: 1
                    jsonlUrl = data.optJSONObject("resultUrl")?.optString("jsonUrl")
                    if (jsonlUrl.isNullOrBlank()) {
                        throw IOException("任务完成但缺少结果地址")
                    }
                    // 配额只在结果下载成功后计入（下载失败不占用本机统计）
                    val url = jsonlUrl
                    val jsonl = client().newCall(Request.Builder().url(url).get().build())
                        .execute().use { resp ->
                            val t = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) {
                                throw IOException("下载结果失败 HTTP ${resp.code}")
                            }
                            t
                        }
                    val parsed = parseJsonl(jsonl)
                    // 云端按“页”计费，图片任务固定 1 页；仅解析成功才累计
                    Prefs.addQuotaUsage(model, pages)
                    return OcrResult(
                        lines = parsed.lines,
                        markdown = parsed.markdown,
                        modelName = model,
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        isCloud = true
                    )
                }
                "failed" -> {
                    throw IOException(data.optString("errorMsg").ifBlank { "识别任务失败" })
                }
                else -> onProgress?.invoke("等待结果…")
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * 解析 AI Studio JSONL 结果。逐行收集：
     * - layoutParsingResults[i].prunedResult.parsing_res_list[].block_content（VL 结构化）
     * - layoutParsingResults[i] 的 markdown 原文（多级兜底）
     * - ocrResults[i].prunedResult.rec_texts（经典 PP-OCR）
     * 若所有行都解析为空则抛错（不再静默吞掉）。
     */
    private fun parseJsonl(text: String): OcrResult {
        val out = mutableListOf<OcrLine>()
        val markdownParts = mutableListOf<String>()
        var parsedLines = 0
        var errorLines = 0

        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            parsedLines++
            try {
                val root = JSONObject(line)
                val result = root.optJSONObject("result") ?: root

                result.optJSONArray("layoutParsingResults")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val pruned = item.optJSONObject("prunedResult") ?: item
                        pruned.optJSONArray("parsing_res_list")?.let { blocks ->
                            for (j in 0 until blocks.length()) {
                                val block = blocks.optJSONObject(j) ?: continue
                                val content = block.optString("block_content")
                                if (content.isNotBlank()) {
                                    content.lineSequence().forEach { ln ->
                                        val t = ln.trim()
                                        if (t.isNotEmpty()) out.add(OcrLine(t, 0.0))
                                    }
                                }
                            }
                        }
                        // markdown 多级兜底：prunedResult → item → result → root
                        listOf(
                            pruned.optJSONObject("markdown"),
                            item.optJSONObject("markdown"),
                            result.optJSONObject("markdown"),
                            root.optJSONObject("markdown")
                        ).forEach { md ->
                            val t = md?.optString("text")
                            if (!t.isNullOrBlank()) markdownParts.add(t)
                        }
                    }
                }

                // 经典 PP-OCR：result.ocrResults[i].prunedResult.rec_texts
                result.optJSONArray("ocrResults")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val pruned = item.optJSONObject("prunedResult") ?: item
                        collectRecTexts(pruned, 0, 0, out)
                    }
                }
            } catch (e: Exception) {
                errorLines++
            }
        }

        if (parsedLines > 0 && out.isEmpty() && markdownParts.isEmpty()) {
            throw IOException(
                if (errorLines > 0) "结果解析失败：$errorLines/${parsedLines} 行异常"
                else "未能从结果中解析出文字"
            )
        }
        return OcrResult(
            lines = out,
            markdown = markdownParts.distinct().joinToString("\n\n").ifBlank { null }
        )
    }
}
