package com.goodcamera.app.processing

import android.graphics.Bitmap
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 人間の視覚系（HVS: Human Visual System）を模倣した知覚的画像補正
 *
 * 人の目と脳が行っている処理を画像処理で再現する:
 *
 * 1. 局所コントラスト適応 (CLAHE)
 *    → 網膜の側抑制: 目は局所的な明暗差に適応し、暗い場所と明るい場所を
 *      同時に知覚できる。グローバルなヒストグラム補正では再現できない。
 *
 * 2. 色恒常性補正 (Gray World)
 *    → 脳の色恒常性: 照明が変わっても物体の色を一定に知覚する能力。
 *      グレーワールド仮定でシーン全体の色かぶりを除去する。
 *
 * 3. 中間トーン彩度ブースト
 *    → 記憶色効果: 人は実際より鮮やかな色を記憶する傾向がある。
 *      中間トーンのみ控えめに彩度を上げることで「見た印象」に近づける。
 *
 * 4. 対数的輝度圧縮
 *    → Weber-Fechner の法則: 人の輝度知覚は対数的。
 *      シャドウを持ち上げハイライトを抑えることで、目で見た印象に近づける。
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

        // Step 1: 色恒常性補正（Gray World）
        applyColorConstancy(pixels)

        // Step 2: 局所コントラスト適応（CLAHE風）
        applyLocalContrastAdaptation(pixels, width, height)

        // Step 3: 対数的輝度圧縮（Weber-Fechner）
        applyPerceptualToneMap(pixels)

        // Step 4: 中間トーン彩度ブースト（記憶色効果）
        applyMemoryColorBoost(pixels)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * 色恒常性補正 (Gray World Assumption)
     *
     * 脳は「世界の平均色はグレー」と仮定して色かぶりを補正している。
     * 各チャンネルの平均値を128に近づけることで、蛍光灯やタングステンの
     * 色かぶりを自動補正する。
     */
    private fun applyColorConstancy(pixels: IntArray) {
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        for (pixel in pixels) {
            sumR += (pixel shr 16) and 0xFF
            sumG += (pixel shr 8) and 0xFF
            sumB += pixel and 0xFF
        }

        val n = pixels.size.toLong()
        val avgR = sumR.toFloat() / n
        val avgG = sumG.toFloat() / n
        val avgB = sumB.toFloat() / n
        val avgAll = (avgR + avgG + avgB) / 3f

        // 補正が極端にならないようクランプ（0.8〜1.2倍）
        val scaleR = (avgAll / avgR.coerceAtLeast(1f)).coerceIn(0.8f, 1.2f)
        val scaleG = (avgAll / avgG.coerceAtLeast(1f)).coerceIn(0.8f, 1.2f)
        val scaleB = (avgAll / avgB.coerceAtLeast(1f)).coerceIn(0.8f, 1.2f)

        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16 and 0xFF) * scaleR).toInt().coerceIn(0, 255)
            val g = ((pixels[i] shr 8 and 0xFF) * scaleG).toInt().coerceIn(0, 255)
            val b = ((pixels[i] and 0xFF) * scaleB).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
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
     * 対数的輝度圧縮 (Weber-Fechner の法則)
     *
     * 人間の輝度知覚は線形ではなく対数的。暗い部分の小さな差は敏感に感じるが、
     * 明るい部分の大きな差にはあまり気づかない。
     * この特性を模倣し、シャドウを持ち上げハイライトを抑える。
     */
    private fun applyPerceptualToneMap(pixels: IntArray) {
        // sRGBトランスファ関数に近い S字カーブを LUT で適用
        // シャドウは少し持ち上げ、ハイライトは少し抑える
        val lut = IntArray(256)
        for (i in 0..255) {
            val x = i / 255f
            // ソフトな S字: シャドウを +10%ほど持ち上げ、ハイライトを -5%抑える
            val lifted = adjustShadowHighlight(x, shadowLift = 0.08f, highlightCompress = 0.03f)
            lut[i] = (lifted * 255f).toInt().coerceIn(0, 255)
        }

        for (i in pixels.indices) {
            val r = lut[(pixels[i] shr 16) and 0xFF]
            val g = lut[(pixels[i] shr 8) and 0xFF]
            val b = lut[pixels[i] and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * シャドウ持ち上げ + ハイライト圧縮のトーンカーブ
     * 3次ベジエ風の滑らかな S 字を生成
     */
    private fun adjustShadowHighlight(
        x: Float,
        shadowLift: Float,
        highlightCompress: Float,
    ): Float {
        // シャドウ: x が小さいほど持ち上げ量が大きい
        val shadow = shadowLift * (1f - x) * (1f - x)
        // ハイライト: x が大きいほど圧縮量が大きい
        val highlight = -highlightCompress * x * x
        return (x + shadow + highlight).coerceIn(0f, 1f)
    }

    /**
     * 中間トーン彩度ブースト (記憶色効果)
     *
     * 人は風景や肌の色を実際より鮮やかに記憶する。
     * 中間輝度のピクセルのみ控えめに彩度を上げ、暗部と明部は自然なまま残す。
     * また、肌色（暖色系）は過度に彩度を上げない。
     */
    private fun applyMemoryColorBoost(pixels: IntArray) {
        val boostAmount = 0.15f // 最大 15% 彩度アップ

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF

            val lum = 0.299f * r + 0.587f * g + 0.114f * b

            // 中間トーン重み: 128付近で最大、0/255で0
            val midWeight = midtoneWeight(lum / 255f)

            // 肌色検出: 暖色系は控えめに
            val isSkinTone = r > g && g > b && r - b > 20 && lum in 60f..200f
            val skinDamping = if (isSkinTone) 0.4f else 1f

            val effectiveBoost = 1f + boostAmount * midWeight * skinDamping

            // 輝度を保持しながら彩度をブースト
            val newR = (lum + (r - lum) * effectiveBoost).toInt().coerceIn(0, 255)
            val newG = (lum + (g - lum) * effectiveBoost).toInt().coerceIn(0, 255)
            val newB = (lum + (b - lum) * effectiveBoost).toInt().coerceIn(0, 255)

            pixels[i] = (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB
        }
    }

    /**
     * 中間トーン重み関数: 0.5 付近で最大=1、端（0, 1）で0に近づく
     * ガウシアン風のベル型カーブ
     */
    private fun midtoneWeight(x: Float): Float {
        val centered = x - 0.5f
        return kotlin.math.exp((-8f * centered * centered).toDouble()).toFloat()
    }
}
