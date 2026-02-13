package com.goodcamera.app.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ピンぼけ・手ブレの自動検出と補正
 *
 * 1. ラプラシアン分散でブラー度を検出
 * 2. ブラー度に応じてマルチスケールのデコンボリューションシャープニングを適用
 *    - 小スケール: 軽微なブレ（手ブレ）
 *    - 中スケール: ピンぼけ
 *    - 大スケール: 重度のピンぼけ
 * 3. 反復適用で段階的にエッジを復元（Richardson-Lucy 近似）
 */
object DeblurFilter {

    /** ブラー検出結果 */
    data class BlurAnalysis(
        val laplacianVariance: Float,
        val blurLevel: BlurLevel,
    )

    enum class BlurLevel {
        SHARP,      // 十分シャープ → 補正不要
        SLIGHT,     // 軽微なブレ
        MODERATE,   // 中程度のピンぼけ/ブレ
        HEAVY,      // 重度のピンぼけ
    }

    /**
     * ラプラシアン分散によるブラー度検出。
     * 高速化のためダウンサンプルした輝度画像で計算する。
     */
    fun detectBlur(bitmap: Bitmap): BlurAnalysis {
        val sampleWidth = 320
        val scale = sampleWidth.toFloat() / bitmap.width
        val sampleHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val sampled = Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
        val width = sampled.width
        val height = sampled.height
        val pixels = IntArray(width * height)
        sampled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (sampled !== bitmap) sampled.recycle()

        // 輝度に変換
        val lum = FloatArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 3x3 ラプラシアンフィルタを適用し分散を計算
        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val laplacian = -4f * lum[idx] +
                        lum[idx - 1] + lum[idx + 1] +
                        lum[idx - width] + lum[idx + width]
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        val mean = sum / count
        val variance = (sumSq / count - mean * mean).toFloat()

        val level = when {
            variance > 800f -> BlurLevel.SHARP
            variance > 400f -> BlurLevel.SLIGHT
            variance > 150f -> BlurLevel.MODERATE
            else -> BlurLevel.HEAVY
        }

        return BlurAnalysis(variance, level)
    }

    /**
     * ブラー度に応じた自動補正を適用。
     * マルチスケールのアンシャープマスクを反復適用し、
     * ピンぼけ（広域ぼけ）と手ブレ（中域ぼけ）の両方を回復する。
     */
    fun correctBlur(bitmap: Bitmap, analysis: BlurAnalysis): Bitmap {
        if (analysis.blurLevel == BlurLevel.SHARP) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // ブラー度に応じてパラメータを決定
        val passes: List<DeblurPass> = when (analysis.blurLevel) {
            BlurLevel.SLIGHT -> listOf(
                DeblurPass(radius = 1, amount = 0.8f, threshold = 6),
            )
            BlurLevel.MODERATE -> listOf(
                DeblurPass(radius = 2, amount = 0.6f, threshold = 4),
                DeblurPass(radius = 1, amount = 0.5f, threshold = 5),
            )
            BlurLevel.HEAVY -> listOf(
                DeblurPass(radius = 3, amount = 0.5f, threshold = 3),
                DeblurPass(radius = 2, amount = 0.5f, threshold = 4),
                DeblurPass(radius = 1, amount = 0.4f, threshold = 5),
            )
            else -> return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        var current = pixels.copyOf()

        // 各パスを反復適用（Richardson-Lucy 近似）
        for (pass in passes) {
            current = applyDeconvPass(current, width, height, pass)
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(current, 0, width, 0, 0, width, height)
        return output
    }

    private data class DeblurPass(
        val radius: Int,
        val amount: Float,
        val threshold: Int,
    )

    /**
     * 1パスのデコンボリューションシャープニング。
     * ガウシアンぼかしとの差分を増幅してエッジを復元する。
     * 通常のアンシャープマスクと異なり、エッジ方向の強度に応じて
     * 適応的に補正量を調整する。
     */
    private fun applyDeconvPass(
        pixels: IntArray,
        width: Int,
        height: Int,
        pass: DeblurPass,
    ): IntArray {
        val blurred = boxBlur2Pass(pixels, width, height, pass.radius)
        val result = IntArray(pixels.size)

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

            // エッジ強度に基づく適応的補正: 差が大きい = エッジ → 強く補正
            val edgeMag = sqrt((diffR * diffR + diffG * diffG + diffB * diffB).toFloat())
            val adaptiveAmount = if (edgeMag > pass.threshold * 3) {
                pass.amount * 1.2f // 強いエッジは積極的に復元
            } else {
                pass.amount
            }

            val r = if (abs(diffR) >= pass.threshold) {
                (oR + (diffR * adaptiveAmount).toInt()).coerceIn(0, 255)
            } else oR

            val g = if (abs(diffG) >= pass.threshold) {
                (oG + (diffG * adaptiveAmount).toInt()).coerceIn(0, 255)
            } else oG

            val b = if (abs(diffB) >= pass.threshold) {
                (oB + (diffB * adaptiveAmount).toInt()).coerceIn(0, 255)
            } else oB

            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return result
    }

    /**
     * 2パスボックスブラー（ガウシアン近似）
     */
    private fun boxBlur2Pass(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val temp = IntArray(pixels.size)
        val result = IntArray(pixels.size)

        // 水平パス
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, width - 1)
                    val idx = y * width + nx
                    sumR += (pixels[idx] shr 16) and 0xFF
                    sumG += (pixels[idx] shr 8) and 0xFF
                    sumB += pixels[idx] and 0xFF
                    count++
                }
                temp[y * width + x] = (0xFF shl 24) or
                        ((sumR / count) shl 16) or
                        ((sumG / count) shl 8) or
                        (sumB / count)
            }
        }

        // 垂直パス
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    val idx = ny * width + x
                    sumR += (temp[idx] shr 16) and 0xFF
                    sumG += (temp[idx] shr 8) and 0xFF
                    sumB += temp[idx] and 0xFF
                    count++
                }
                result[y * width + x] = (0xFF shl 24) or
                        ((sumR / count) shl 16) or
                        ((sumG / count) shl 8) or
                        (sumB / count)
            }
        }

        return result
    }
}
