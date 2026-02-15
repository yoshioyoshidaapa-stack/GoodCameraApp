package com.goodcamera.app.processing

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * バイラテラルフィルタによるノイズリダクション
 *
 * 空間的な距離と色の差の両方を考慮してフィルタリングするため、
 * エッジを保持しつつノイズを効果的に除去する。
 * タブレットのイマイチなカメラで発生しやすいランダムノイズに有効。
 */
object NoiseReduction {

    /**
     * バイラテラルフィルタを適用
     * @param bitmap 入力画像
     * @param radius フィルタ半径（大きいほど強いノイズ除去、遅い）
     * @param sigmaSpace 空間方向のガウシアン標準偏差
     * @param sigmaColor 色方向のガウシアン標準偏差（大きいほどエッジがぼける）
     */
    fun bilateralFilter(
        bitmap: Bitmap,
        radius: Int = 3,
        sigmaSpace: Float = 10f,
        sigmaColor: Float = 30f,
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val result = IntArray(width * height)

        // 空間重みのルックアップテーブルを事前計算
        val kernelWidth = 2 * radius + 1
        val spatialWeights = FloatArray(kernelWidth * kernelWidth)
        val spatialDenom = 2f * sigmaSpace * sigmaSpace
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val dist = (dx * dx + dy * dy).toFloat()
                spatialWeights[(dy + radius) * kernelWidth + (dx + radius)] =
                    exp(-dist / spatialDenom)
            }
        }

        // 色差重みのLUT: colorDist の最大値は 3*255^2 = 195075
        // 実際にはsigmaColor=40でも dist>3000 程度で重みが≒0になるので切り詰め可能
        val colorDenom = 2f * sigmaColor * sigmaColor
        val maxColorDist = (6f * sigmaColor * sigmaColor).toInt().coerceAtMost(195075)
        val colorWeightLut = FloatArray(maxColorDist + 1)
        for (d in 0..maxColorDist) {
            colorWeightLut[d] = exp(-d.toFloat() / colorDenom)
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val centerIdx = y * width + x
                val cR = (pixels[centerIdx] shr 16) and 0xFF
                val cG = (pixels[centerIdx] shr 8) and 0xFF
                val cB = pixels[centerIdx] and 0xFF

                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var weightSum = 0f

                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue

                        val nIdx = ny * width + nx
                        val nR = (pixels[nIdx] shr 16) and 0xFF
                        val nG = (pixels[nIdx] shr 8) and 0xFF
                        val nB = pixels[nIdx] and 0xFF

                        // 色差（LUT参照 — exp()呼び出しを排除）
                        val colorDist = (cR - nR) * (cR - nR) +
                                (cG - nG) * (cG - nG) +
                                (cB - nB) * (cB - nB)
                        val colorWeight = if (colorDist <= maxColorDist) {
                            colorWeightLut[colorDist]
                        } else {
                            0f // 色差が大きすぎる → 重みゼロ（エッジ保護）
                        }

                        val spatialWeight =
                            spatialWeights[(dy + radius) * kernelWidth + (dx + radius)]
                        val weight = spatialWeight * colorWeight

                        sumR += nR * weight
                        sumG += nG * weight
                        sumB += nB * weight
                        weightSum += weight
                    }
                }

                val r = (sumR / weightSum).toInt().coerceIn(0, 255)
                val g = (sumG / weightSum).toInt().coerceIn(0, 255)
                val b = (sumB / weightSum).toInt().coerceIn(0, 255)
                result[centerIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * 高速近似ノイズリダクション（3x3メディアンフィルタ風）
     * バイラテラルが重すぎる場合のフォールバック
     */
    fun fastDenoise(bitmap: Bitmap, strength: Float = 0.5f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val result = IntArray(width * height)
        val alpha = strength.coerceIn(0f, 1f)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                var avgR = 0
                var avgG = 0
                var avgB = 0

                // 3x3 の平均を計算
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nIdx = (y + dy) * width + (x + dx)
                        avgR += (pixels[nIdx] shr 16) and 0xFF
                        avgG += (pixels[nIdx] shr 8) and 0xFF
                        avgB += pixels[nIdx] and 0xFF
                    }
                }
                avgR /= 9
                avgG /= 9
                avgB /= 9

                // 元のピクセルと平均のブレンド
                val oR = (pixels[idx] shr 16) and 0xFF
                val oG = (pixels[idx] shr 8) and 0xFF
                val oB = pixels[idx] and 0xFF

                val r = (oR * (1f - alpha) + avgR * alpha).toInt().coerceIn(0, 255)
                val g = (oG * (1f - alpha) + avgG * alpha).toInt().coerceIn(0, 255)
                val b = (oB * (1f - alpha) + avgB * alpha).toInt().coerceIn(0, 255)

                result[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // 端のピクセルはそのままコピー
        for (x in 0 until width) {
            result[x] = pixels[x]
            result[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            result[y * width] = pixels[y * width]
            result[y * width + width - 1] = pixels[y * width + width - 1]
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }
}
