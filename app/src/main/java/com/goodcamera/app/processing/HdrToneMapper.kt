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

    // sRGBガンマ→リニア変換のLUT（256エントリ、毎ピクセルのpow()呼び出しを排除）
    private val gammaToLinearLut = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    // リニア→sRGBガンマ変換のLUT（4096エントリで十分な精度）
    private val linearToGammaLut = FloatArray(4096) { i ->
        (i / 4095f).pow(1f / 2.2f)
    }

    // 三角重みLUT（256エントリ）
    private val triangleWeightLut = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.5f) (v * 2f).coerceAtLeast(0.01f) else ((1f - v) * 2f).coerceAtLeast(0.01f)
    }

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
            val invExposure = 1f / 2f.pow(frameIdx - midIdx)

            for (i in pixels.indices) {
                val ri = (pixels[i] shr 16) and 0xFF
                val gi = (pixels[i] shr 8) and 0xFF
                val bi = pixels[i] and 0xFF

                // 重み関数: LUTで参照（中間トーンを高重み）
                val luminanceIdx = ((54 * ri + 183 * gi + 18 * bi) shr 8).coerceIn(0, 255)
                val weight = triangleWeightLut[luminanceIdx]

                // LUTで逆ガンマ→リニア変換（毎ピクセルのpow()を排除）
                val linearR = gammaToLinearLut[ri] * invExposure
                val linearG = gammaToLinearLut[gi] * invExposure
                val linearB = gammaToLinearLut[bi] * invExposure

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

        // Reinhardトーンマッピング（hdr配列をインプレースで再利用）
        val logAvgLuminance = computeLogAverageLuminance(hdrR, hdrG, hdrB, pixelCount)
        val key = 0.18f / (logAvgLuminance + 0.001f)

        for (i in 0 until pixelCount) {
            val kr = hdrR[i] * key; hdrR[i] = kr / (1f + kr)
            val kg = hdrG[i] * key; hdrG[i] = kg / (1f + kg)
            val kb = hdrB[i] * key; hdrB[i] = kb / (1f + kb)
        }

        // 彩度調整 & ガンマ補正 → 8bit出力
        val result = IntArray(pixelCount)
        val lutSize = linearToGammaLut.size - 1

        for (i in 0 until pixelCount) {
            val tmR = hdrR[i]; val tmG = hdrG[i]; val tmB = hdrB[i]
            val lum = 0.2126f * tmR + 0.7152f * tmG + 0.0722f * tmB

            // 彩度調整 + ガンマ補正（LUT参照でpow()を排除）
            var r = (lum + saturation * (tmR - lum)).coerceIn(0f, 1f)
            var g = (lum + saturation * (tmG - lum)).coerceIn(0f, 1f)
            var b = (lum + saturation * (tmB - lum)).coerceIn(0f, 1f)

            r = linearToGammaLut[(r * lutSize).toInt().coerceIn(0, lutSize)]
            g = linearToGammaLut[(g * lutSize).toInt().coerceIn(0, lutSize)]
            b = linearToGammaLut[(b * lutSize).toInt().coerceIn(0, lutSize)]

            val iR = (r * 255f).toInt().coerceIn(0, 255)
            val iG = (g * 255f).toInt().coerceIn(0, 255)
            val iB = (b * 255f).toInt().coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (iR shl 16) or (iG shl 8) or iB
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
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

}
