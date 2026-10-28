package com.goodcamera.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Claudeに送るための写真を用意する
 *
 * 撮影した写真はそのままだと大きすぎるので、長辺 1568px 以下に縮小した JPEG にする
 * （これより大きくてもClaude側で縮小されるだけで、通信量と料金が増える）
 */
object PhotoForClaude {

    private const val MAX_LONG_EDGE = 1568
    private const val JPEG_QUALITY = 85

    fun loadJpeg(context: Context, uri: Uri): ByteArray? {
        return try {
            // まず画像サイズだけ読み、メモリを節約できる縮小率を決める
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
            if (longEdge <= 0) return null

            var sampleSize = 1
            while (longEdge / (sampleSize * 2) >= MAX_LONG_EDGE) sampleSize *= 2

            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            } ?: return null

            val scale = MAX_LONG_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt(),
                    (decoded.height * scale).toInt(),
                    true,
                )
            } else {
                decoded
            }

            ByteArrayOutputStream().use { out ->
                resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }
}
