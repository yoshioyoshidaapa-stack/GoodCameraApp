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

        // Step 1: ボックスブラーで「ぼかし版」を作成
        val blurred = FilterUtils.boxBlur2Pass(pixels, width, height, radius)

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
}
