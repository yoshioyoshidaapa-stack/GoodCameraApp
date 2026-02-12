package com.goodcamera.app.processing

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.pow

/**
 * Reinhard グローバルトーンマッピングによるHDR合成
 *
 * 複数露出のフレームを統合し、白飛び・黒潰れのない
 * ハイダイナミックレンジ画像を生成する。
 */
object HdrToneMapper {

    /**
     * 露出ブラケットフレームをHDR合成
     *
     * 1. 各フレームの露出重みを計算（中間輝度に近いほど高重み）
     * 2. 重み付き合成でHDR輝度マップを生成
     * 3. Reinhardトーンマッピングで表示可能な範囲に圧縮
     *
     * @param frames 露出の異なる複数フレーム（暗→明の順）
     * @param gamma ガンマ補正値
     * @param saturation 彩度調整（1.0=元のまま、>1.0=鮮やか）
     */
    fun mergeAndToneMap(
        frames: List<Bitmap>,
        gamma: Float = 1.0f,
        saturation: Float = 1.1f,
    ): Bitmap {
        if (frames.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val width = frames[0].width
        val height = frames[0].height
        val pixelCount = width * height

        // HDR放射輝度マップ（float）
        val hdrR = FloatArray(pixelCount)
        val hdrG = FloatArray(pixelCount)
        val hdrB = FloatArray(pixelCount)
        val weightSum = FloatArray(pixelCount)

        // 各フレームから重み付き合成
        for ((frameIdx, frame) in frames.withIndex()) {
            val pixels = IntArray(pixelCount)
            frame.getPixels(pixels, 0, width, 0, 0, width, height)

            // フレームの相対露出値（中央フレームを基準=1.0）
            val midIdx = frames.size / 2
            val relativeExposure = 2f.pow(frameIdx - midIdx)

            for (i in pixels.indices) {
                val r = ((pixels[i] shr 16) and 0xFF) / 255f
                val g = ((pixels[i] shr 8) and 0xFF) / 255f
                val b = (pixels[i] and 0xFF) / 255f

                // 重み関数: 中間トーンを高重み、飽和・暗部は低重み
                val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val weight = triangleWeight(luminance)

                // 逆ガンマ→リニア→HDR放射輝度に変換
                val linearR = gammaToLinear(r) / relativeExposure
                val linearG = gammaToLinear(g) / relativeExposure
                val linearB = gammaToLinear(b) / relativeExposure

                hdrR[i] += linearR * weight
                hdrG[i] += linearG * weight
                hdrB[i] += linearB * weight
                weightSum[i] += weight
            }
        }

        // 重みで正規化
        for (i in 0 until pixelCount) {
            val w = if (weightSum[i] > 0f) weightSum[i] else 1f
            hdrR[i] /= w
            hdrG[i] /= w
            hdrB[i] /= w
        }

        // Reinhardトーンマッピング
        val toneMapR = FloatArray(pixelCount)
        val toneMapG = FloatArray(pixelCount)
        val toneMapB = FloatArray(pixelCount)

        // シーンの平均輝度（対数平均）を計算
        val logAvgLuminance = computeLogAverageLuminance(hdrR, hdrG, hdrB, pixelCount)
        val key = 0.18f / (logAvgLuminance + 0.001f)

        for (i in 0 until pixelCount) {
            toneMapR[i] = reinhardToneMap(hdrR[i] * key)
            toneMapG[i] = reinhardToneMap(hdrG[i] * key)
            toneMapB[i] = reinhardToneMap(hdrB[i] * key)
        }

        // 彩度調整 & ガンマ補正 → 8bit出力
        val result = IntArray(pixelCount)
        val invGamma = 1f / gamma.coerceAtLeast(0.1f)

        for (i in 0 until pixelCount) {
            val lum = 0.2126f * toneMapR[i] + 0.7152f * toneMapG[i] + 0.0722f * toneMapB[i]
            val lumSafe = lum.coerceAtLeast(0.001f)

            // 彩度調整
            var r = lum + saturation * (toneMapR[i] - lum)
            var g = lum + saturation * (toneMapG[i] - lum)
            var b = lum + saturation * (toneMapB[i] - lum)

            // ガンマ補正
            r = linearToGamma(r.coerceAtLeast(0f), invGamma)
            g = linearToGamma(g.coerceAtLeast(0f), invGamma)
            b = linearToGamma(b.coerceAtLeast(0f), invGamma)

            val iR = (r * 255f).toInt().coerceIn(0, 255)
            val iG = (g * 255f).toInt().coerceIn(0, 255)
            val iB = (b * 255f).toInt().coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (iR shl 16) or (iG shl 8) or iB
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }

    /** Reinhard グローバルオペレータ: L_d = L / (1 + L) */
    private fun reinhardToneMap(luminance: Float): Float {
        return luminance / (1f + luminance)
    }

    /** 三角重み関数: 0.5付近で最大、0と1付近でゼロに近づく */
    private fun triangleWeight(value: Float): Float {
        return if (value <= 0.5f) {
            (value * 2f).coerceAtLeast(0.01f)
        } else {
            ((1f - value) * 2f).coerceAtLeast(0.01f)
        }
    }

    /** 対数平均輝度の計算 */
    private fun computeLogAverageLuminance(
        r: FloatArray,
        g: FloatArray,
        b: FloatArray,
        count: Int,
    ): Float {
        var logSum = 0.0
        val delta = 0.0001f
        for (i in 0 until count) {
            val luminance = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
            logSum += ln((luminance + delta).toDouble())
        }
        return kotlin.math.exp(logSum / count).toFloat()
    }

    /** sRGBガンマ→リニア変換の近似 */
    private fun gammaToLinear(value: Float): Float {
        return if (value <= 0.04045f) value / 12.92f
        else ((value + 0.055f) / 1.055f).pow(2.4f)
    }

    /** リニア→sRGBガンマ変換 */
    private fun linearToGamma(value: Float, invGamma: Float = 1f / 2.2f): Float {
        return value.pow(invGamma)
    }
}
