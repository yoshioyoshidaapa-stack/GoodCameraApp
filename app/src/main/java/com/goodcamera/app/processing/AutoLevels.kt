package com.goodcamera.app.processing

import android.graphics.Bitmap

/**
 * ヒストグラムベースの自動レベル補正
 *
 * タブレットカメラの露出制御が不完全な場合でも、
 * ソフトウェアでコントラストとトーンを最適化する。
 */
object AutoLevels {

    /**
     * 自動レベル補正を適用
     * ヒストグラムの上下一定%をクリップし、残りの範囲を0-255に引き伸ばす。
     *
     * @param bitmap 入力画像
     * @param clipPercent クリッピング率（0.5〜2.0% 推奨）
     */
    fun autoAdjust(bitmap: Bitmap, clipPercent: Float = 1.0f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 各チャンネルのヒストグラムを計算
        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)

        for (pixel in pixels) {
            histR[(pixel shr 16) and 0xFF]++
            histG[(pixel shr 8) and 0xFF]++
            histB[pixel and 0xFF]++
        }

        val totalPixels = width * height
        val clipCount = (totalPixels * clipPercent / 100f).toInt()

        // 各チャンネルのクリップ範囲を計算
        val (minR, maxR) = findClipRange(histR, clipCount)
        val (minG, maxG) = findClipRange(histG, clipCount)
        val (minB, maxB) = findClipRange(histB, clipCount)

        // ルックアップテーブルを生成
        val lutR = buildLut(minR, maxR)
        val lutG = buildLut(minG, maxG)
        val lutB = buildLut(minB, maxB)

        // 適用
        val result = IntArray(width * height)
        for (i in pixels.indices) {
            val r = lutR[(pixels[i] shr 16) and 0xFF]
            val g = lutG[(pixels[i] shr 8) and 0xFF]
            val b = lutB[pixels[i] and 0xFF]
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * 自動コントラスト（輝度のみ補正、色相は保持）
     */
    fun autoContrast(bitmap: Bitmap, clipPercent: Float = 0.5f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 輝度ヒストグラム
        val histL = IntArray(256)
        val luminances = IntArray(width * height)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val l = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            luminances[i] = l
            histL[l]++
        }

        val totalPixels = width * height
        val clipCount = (totalPixels * clipPercent / 100f).toInt()
        val (minL, maxL) = findClipRange(histL, clipCount)

        if (maxL <= minL) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val scale = 255f / (maxL - minL)

        val result = IntArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val l = luminances[i]

            if (l == 0) {
                result[i] = pixels[i]
                continue
            }

            // 輝度を補正し、色比率は維持
            val newL = ((l - minL) * scale).coerceIn(0f, 255f)
            val ratio = newL / l

            val newR = (r * ratio).toInt().coerceIn(0, 255)
            val newG = (g * ratio).toInt().coerceIn(0, 255)
            val newB = (b * ratio).toInt().coerceIn(0, 255)

            result[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }

    private fun findClipRange(histogram: IntArray, clipCount: Int): Pair<Int, Int> {
        var min = 0
        var sum = 0
        while (min < 255 && sum < clipCount) {
            sum += histogram[min]
            min++
        }

        var max = 255
        sum = 0
        while (max > 0 && sum < clipCount) {
            sum += histogram[max]
            max--
        }

        if (min >= max) {
            min = 0
            max = 255
        }
        return Pair(min, max)
    }

    private fun buildLut(min: Int, max: Int): IntArray {
        val lut = IntArray(256)
        val range = (max - min).coerceAtLeast(1)
        for (i in 0..255) {
            lut[i] = ((i - min) * 255f / range).toInt().coerceIn(0, 255)
        }
        return lut
    }
}
