package com.goodcamera.app.processing

import android.graphics.Bitmap

/**
 * 人間の視覚系（HVS: Human Visual System）を模倣した知覚的画像補正
 *
 * 人の目と脳が行っている処理を画像処理で再現する:
 *
 * 1. 局所コントラスト適応 (CLAHE)
 *    → 網膜の側抑制: 目は局所的な明暗差に適応し、暗い場所と明るい場所を
 *      同時に知覚できる。グローバルなヒストグラム補正では再現できない。
 *
 * 2. 中間トーン彩度ブースト
 *    → 記憶色効果: 人は実際より鮮やかな色を記憶する傾向がある。
 *      中間トーンのみ控えめに彩度を上げることで「見た印象」に近づける。
 *
 * 3. 対数的輝度圧縮
 *    → Weber-Fechner の法則: 人の輝度知覚は対数的。
 *      シャドウを持ち上げハイライトを抑えることで、目で見た印象に近づける。
 *
 * ※ 色恒常性（ホワイトバランス）は WhiteBalanceCorrector に分離済み
 */
object PerceptualEnhancer {

    /**
     * 全ての知覚補正を適用する統合メソッド
     */
    fun enhance(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Step 1: 局所コントラスト適応（CLAHE風）
        applyLocalContrastAdaptation(pixels, width, height)

        // Step 2: 対数的輝度圧縮 + 中間トーン彩度ブースト（1パス統合）
        applyPerceptualToneAndColorBoost(pixels)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * 局所コントラスト適応 (CLAHE 風)
     *
     * 網膜の側抑制をシミュレート: 各ピクセルの周囲の平均輝度に基づいて
     * ローカルにコントラストを調整する。
     * これにより暗部のディテールと明部のディテールを同時に引き出せる。
     *
     * フルCLAHEの代わりに、ダウンサンプルした局所平均輝度マップとの差分を
     * ブレンドする高速近似を使う。
     */
    private fun applyLocalContrastAdaptation(pixels: IntArray, width: Int, height: Int) {
        // 輝度を抽出
        val luminance = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            luminance[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 局所平均輝度マップ（大きめカーネルでボックスブラー）
        val localMean = computeLocalMean(luminance, width, height, blockSize = 32)

        val strength = 0.5f // 補正強度 (0=無効, 1=フル)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            val lum = luminance[i]
            val localAvg = localMean[i].coerceAtLeast(1f)

            // 側抑制: (ピクセル輝度 - 局所平均) を増幅してコントラスト強調
            // ただし全体の明るさは保持
            val target = 128f
            val gain = 1f + strength * (target / localAvg - 1f)

            // gain をクランプして破綻を防ぐ（0.6〜1.8倍）
            val clampedGain = gain.coerceIn(0.6f, 1.8f)

            val newR = (r * clampedGain).toInt().coerceIn(0, 255)
            val newG = (g * clampedGain).toInt().coerceIn(0, 255)
            val newB = (b * clampedGain).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }
    }

    /**
     * ダウンサンプル → ブラー → アップサンプルで高速な局所平均輝度マップを作成
     */
    private fun computeLocalMean(
        luminance: FloatArray,
        width: Int,
        height: Int,
        blockSize: Int,
    ): FloatArray {
        // ダウンサンプル
        val dsW = (width + blockSize - 1) / blockSize
        val dsH = (height + blockSize - 1) / blockSize
        val downsampled = FloatArray(dsW * dsH)
        val downsampledCount = IntArray(dsW * dsH)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x / blockSize
                val dy = y / blockSize
                val di = dy * dsW + dx
                downsampled[di] += luminance[y * width + x]
                downsampledCount[di]++
            }
        }

        for (i in downsampled.indices) {
            if (downsampledCount[i] > 0) {
                downsampled[i] /= downsampledCount[i]
            }
        }

        // 3x3 スムージング（ブロック境界のアーティファクトを軽減）
        val smoothed = FloatArray(dsW * dsH)
        for (y in 0 until dsH) {
            for (x in 0 until dsW) {
                var sum = 0f; var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, dsW - 1)
                        val ny = (y + dy).coerceIn(0, dsH - 1)
                        sum += downsampled[ny * dsW + nx]
                        count++
                    }
                }
                smoothed[y * dsW + x] = sum / count
            }
        }

        // バイリニア補間でアップサンプル
        val result = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val fx = (x.toFloat() / blockSize).coerceIn(0f, (dsW - 1).toFloat())
                val fy = (y.toFloat() / blockSize).coerceIn(0f, (dsH - 1).toFloat())
                val ix = fx.toInt().coerceAtMost(dsW - 2)
                val iy = fy.toInt().coerceAtMost(dsH - 2)
                val dx = fx - ix
                val dy = fy - iy

                val v00 = smoothed[iy * dsW + ix]
                val v10 = smoothed[iy * dsW + ix + 1]
                val v01 = smoothed[(iy + 1) * dsW + ix]
                val v11 = smoothed[(iy + 1) * dsW + ix + 1]

                result[y * width + x] = v00 * (1 - dx) * (1 - dy) +
                        v10 * dx * (1 - dy) +
                        v01 * (1 - dx) * dy +
                        v11 * dx * dy
            }
        }

        return result
    }

    /**
     * 対数的輝度圧縮 + 中間トーン彩度ブーストを1パスで適用
     *
     * 旧実装では2パス（トーンマップ→カラーブースト）に分かれていたが、
     * LUTの事前計算で統合可能。
     *
     * - Weber-Fechner: シャドウを持ち上げハイライトを抑える
     * - 記憶色効果: 中間トーンのみ控えめに彩度を上げる
     */
    private fun applyPerceptualToneAndColorBoost(pixels: IntArray) {
        // トーンマップLUT
        val toneLut = IntArray(256)
        for (i in 0..255) {
            val x = i / 255f
            val shadow = 0.08f * (1f - x) * (1f - x)
            val highlight = -0.03f * x * x
            toneLut[i] = ((x + shadow + highlight).coerceIn(0f, 1f) * 255f).toInt()
        }

        // 中間トーン重みLUT（exp()のDouble変換を排除）
        val midWeightLut = FloatArray(256)
        for (i in 0..255) {
            val centered = i / 255f - 0.5f
            midWeightLut[i] = kotlin.math.exp(-8f * centered * centered)
        }

        val boostAmount = 0.15f

        for (i in pixels.indices) {
            // トーンマップ適用
            val r = toneLut[(pixels[i] shr 16) and 0xFF]
            val g = toneLut[(pixels[i] shr 8) and 0xFF]
            val b = toneLut[pixels[i] and 0xFF]

            // 彩度ブースト
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val midWeight = midWeightLut[lum.toInt().coerceIn(0, 255)]

            // 肌色検出: 暖色系は控えめに
            val skinDamping = if (r > g && g > b && r - b > 20 && lum in 60f..200f) 0.4f else 1f
            val effectiveBoost = 1f + boostAmount * midWeight * skinDamping

            val newR = (lum + (r - lum) * effectiveBoost).toInt().coerceIn(0, 255)
            val newG = (lum + (g - lum) * effectiveBoost).toInt().coerceIn(0, 255)
            val newB = (lum + (b - lum) * effectiveBoost).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }
    }
}
