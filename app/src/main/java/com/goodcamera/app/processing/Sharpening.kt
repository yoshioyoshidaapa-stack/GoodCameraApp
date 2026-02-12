package com.goodcamera.app.processing

import android.graphics.Bitmap

/**
 * Unsharp Mask によるシャープニング
 *
 * タブレットカメラの安価なレンズによる解像感不足を補償。
 * ぼかし画像を元画像から差し引いてエッジを強調する。
 */
object Sharpening {

    /**
     * Unsharp Mask を適用
     * @param bitmap 入力画像
     * @param amount シャープニング強度（0.0〜3.0、通常1.0〜1.5）
     * @param radius ぼかし半径
     * @param threshold 適用する最小の差（ノイズ強調を防ぐ）
     */
    fun unsharpMask(
        bitmap: Bitmap,
        amount: Float = 1.2f,
        radius: Int = 1,
        threshold: Int = 4,
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Step 1: ガウシアンブラーで「ぼかし版」を作成
        val blurred = gaussianBlur(pixels, width, height, radius)

        // Step 2: 差分を増幅してエッジ強調
        val result = IntArray(width * height)
        for (i in pixels.indices) {
            val oR = (pixels[i] shr 16) and 0xFF
            val oG = (pixels[i] shr 8) and 0xFF
            val oB = pixels[i] and 0xFF

            val bR = (blurred[i] shr 16) and 0xFF
            val bG = (blurred[i] shr 8) and 0xFF
            val bB = blurred[i] and 0xFF

            val diffR = oR - bR
            val diffG = oG - bG
            val diffB = oB - bB

            // 差分がthreshold以下なら適用しない（ノイズ保護）
            val r = if (kotlin.math.abs(diffR) >= threshold) {
                (oR + (diffR * amount).toInt()).coerceIn(0, 255)
            } else oR

            val g = if (kotlin.math.abs(diffG) >= threshold) {
                (oG + (diffG * amount).toInt()).coerceIn(0, 255)
            } else oG

            val b = if (kotlin.math.abs(diffB) >= threshold) {
                (oB + (diffB * amount).toInt()).coerceIn(0, 255)
            } else oB

            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * 簡易ガウシアンブラー（ボックスブラー2パスで近似）
     */
    private fun gaussianBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val temp = IntArray(width * height)
        val result = IntArray(width * height)
        val kernelSize = 2 * radius + 1

        // 水平パス
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0

                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, width - 1)
                    val idx = y * width + nx
                    sumR += (pixels[idx] shr 16) and 0xFF
                    sumG += (pixels[idx] shr 8) and 0xFF
                    sumB += pixels[idx] and 0xFF
                    count++
                }

                val r = sumR / count
                val g = sumG / count
                val b = sumB / count
                temp[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // 垂直パス
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0

                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val idx = ny * width + x
                    sumR += (temp[idx] shr 16) and 0xFF
                    sumG += (temp[idx] shr 8) and 0xFF
                    sumB += temp[idx] and 0xFF
                    count++
                }

                val r = sumR / count
                val g = sumG / count
                val b = sumB / count
                result[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return result
    }
}
