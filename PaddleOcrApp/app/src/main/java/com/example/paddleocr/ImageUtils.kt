package com.example.paddleocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.IOException

object ImageUtils {

    /**
     * 按最大边长降采样加载图片，并根据 EXIF 信息自动旋转。
     */
    fun loadBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = resolver.openInputStream(uri) ?: throw IOException("无法读取图片")
        boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("图片尺寸无效")

        var sample = 1
        val maxEdge = maxOf(bounds.outWidth, bounds.outHeight)
        // 标准降采样：解码后边长不超过 maxDim，避免高像素照片（边长在 maxDim~2×maxDim 区间）
        // 被全尺寸解码导致 OOM
        while (maxEdge / sample >= maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val imageInput = resolver.openInputStream(uri) ?: throw IOException("无法读取图片")
        val decoded = try {
            imageInput.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: OutOfMemoryError) {
            throw IOException("图片过大，内存不足", e)
        }
            ?: throw IOException("图片解码失败")

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSPOSE -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSVERSE -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (rotation == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotation) }
        return try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                // 旋转会复制一份像素，及时释放原图，降低内存峰值
                if (it !== decoded) decoded.recycle()
            }
        } catch (e: OutOfMemoryError) {
            decoded.recycle()
            throw IOException("图片过大，内存不足", e)
        }
    }
}
