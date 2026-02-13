package com.goodcamera.app.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ソフトウェア自動ホワイトバランス補正
 *
 * Camera2 のハードウェア AWB が不十分な場合（タブレットの安価なISPなど）に
 * ソフトウェアで追加補正する。3つのアルゴリズムを統合して堅牢性を高める:
 *
 * 1. Gray World: シーン全体の平均色がグレーになるようゲイン調整
 * 2. White Patch Retinex: 最も明るいピクセルが白になるようゲイン調整
 * 3. Shades of Gray (p-norm): Minkowski p-norm で重みを付けた平均
 *
 * 各手法のゲインを照明推定の信頼度で加重平均し、最終的な補正ゲインを算出する。
 * さらに色温度シフトの検出と緑かぶり補正も行う。
 */
object WhiteBalanceCorrector {

    data class WbGains(val r: Float, val g: Float, val b: Float)

    /**
     * 自動ホワイトバランス補正を適用
     */
    fun correct(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 飽和・暗部のピクセルを除外したサンプリング
        val samples = sampleValidPixels(pixels)

        if (samples.isEmpty()) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // 3つのアルゴリズムでゲインを推定
        val grayWorldGains = estimateGrayWorld(samples)
        val whitePatchGains = estimateWhitePatch(pixels)
        val shadesOfGrayGains = estimateShadesOfGray(samples, p = 6f)

        // 各推定の信頼度を計算して加重平均
        val gwConfidence = computeConfidence(grayWorldGains)
        val wpConfidence = computeConfidence(whitePatchGains)
        val sogConfidence = computeConfidence(shadesOfGrayGains)

        val totalConf = gwConfidence + wpConfidence + sogConfidence
        val finalGains = if (totalConf > 0f) {
            WbGains(
                r = (grayWorldGains.r * gwConfidence + whitePatchGains.r * wpConfidence + shadesOfGrayGains.r * sogConfidence) / totalConf,
                g = (grayWorldGains.g * gwConfidence + whitePatchGains.g * wpConfidence + shadesOfGrayGains.g * sogConfidence) / totalConf,
                b = (grayWorldGains.b * gwConfidence + whitePatchGains.b * wpConfidence + shadesOfGrayGains.b * sogConfidence) / totalConf,
            )
        } else {
            WbGains(1f, 1f, 1f)
        }

        // 緑かぶり補正
        val correctedGains = correctGreenTint(finalGains)

        // ゲインを正規化（最小ゲインが1.0になるようスケール）
        val minGain = minOf(correctedGains.r, correctedGains.g, correctedGains.b).coerceAtLeast(0.01f)
        val normalizedGains = WbGains(
            r = correctedGains.r / minGain,
            g = correctedGains.g / minGain,
            b = correctedGains.b / minGain,
        )

        // LUTを使って高速適用
        val lutR = IntArray(256) { i -> (i * normalizedGains.r).toInt().coerceIn(0, 255) }
        val lutG = IntArray(256) { i -> (i * normalizedGains.g).toInt().coerceIn(0, 255) }
        val lutB = IntArray(256) { i -> (i * normalizedGains.b).toInt().coerceIn(0, 255) }

        for (i in pixels.indices) {
            val r = lutR[(pixels[i] shr 16) and 0xFF]
            val g = lutG[(pixels[i] shr 8) and 0xFF]
            val b = lutB[pixels[i] and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * 飽和・暗部を除外した有効ピクセルのサンプリング
     * 白飛び/黒潰れのピクセルはWB推定を狂わせるので除外する
     */
    private fun sampleValidPixels(pixels: IntArray): List<Triple<Int, Int, Int>> {
        val samples = mutableListOf<Triple<Int, Int, Int>>()
        // 全ピクセルを見ると遅いので4ピクセルおきにサンプリング
        val step = 4
        for (i in pixels.indices step step) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF

            // 暗すぎ(< 15)・明るすぎ(> 240)を除外
            if (r in 15..240 && g in 15..240 && b in 15..240) {
                samples.add(Triple(r, g, b))
            }
        }
        return samples
    }

    /**
     * Gray World: 世界の平均色はグレーであるという仮定
     */
    private fun estimateGrayWorld(samples: List<Triple<Int, Int, Int>>): WbGains {
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        for ((r, g, b) in samples) {
            sumR += r; sumG += g; sumB += b
        }
        val n = samples.size.toDouble()
        val avgR = (sumR / n).toFloat()
        val avgG = (sumG / n).toFloat()
        val avgB = (sumB / n).toFloat()
        val avgAll = (avgR + avgG + avgB) / 3f

        return WbGains(
            r = avgAll / avgR.coerceAtLeast(1f),
            g = avgAll / avgG.coerceAtLeast(1f),
            b = avgAll / avgB.coerceAtLeast(1f),
        )
    }

    /**
     * White Patch Retinex: 最も明るいピクセル群が白であるという仮定
     * 上位 0.5% の最も明るいピクセルの平均色を白点として使用
     */
    private fun estimateWhitePatch(pixels: IntArray): WbGains {
        // 輝度でソートして上位を取得
        data class PixelLum(val r: Int, val g: Int, val b: Int, val lum: Float)

        val step = 8
        val lumPixels = mutableListOf<PixelLum>()
        for (i in pixels.indices step step) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            // 完全に飽和したピクセルは除外
            if (r < 250 && g < 250 && b < 250) {
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                lumPixels.add(PixelLum(r, g, b, lum))
            }
        }

        if (lumPixels.isEmpty()) return WbGains(1f, 1f, 1f)

        lumPixels.sortByDescending { it.lum }
        val topCount = (lumPixels.size * 0.005f).toInt().coerceAtLeast(10).coerceAtMost(lumPixels.size)

        var sumR = 0f; var sumG = 0f; var sumB = 0f
        for (i in 0 until topCount) {
            sumR += lumPixels[i].r
            sumG += lumPixels[i].g
            sumB += lumPixels[i].b
        }

        val avgR = sumR / topCount
        val avgG = sumG / topCount
        val avgB = sumB / topCount
        val maxChannel = maxOf(avgR, avgG, avgB).coerceAtLeast(1f)

        return WbGains(
            r = maxChannel / avgR.coerceAtLeast(1f),
            g = maxChannel / avgG.coerceAtLeast(1f),
            b = maxChannel / avgB.coerceAtLeast(1f),
        )
    }

    /**
     * Shades of Gray: Minkowski p-norm を使った一般化 Gray World
     * p=1 で Gray World、p=∞ で White Patch に相当
     * p=6 が多くのシーンで良好な結果を出すことが知られている (Finlayson & Trezzi, 2004)
     */
    private fun estimateShadesOfGray(samples: List<Triple<Int, Int, Int>>, p: Float): WbGains {
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0

        for ((r, g, b) in samples) {
            sumR += (r / 255f).toDouble().pow(p.toDouble())
            sumG += (g / 255f).toDouble().pow(p.toDouble())
            sumB += (b / 255f).toDouble().pow(p.toDouble())
        }

        val n = samples.size.toDouble()
        val invP = 1.0 / p
        val normR = (sumR / n).pow(invP).toFloat()
        val normG = (sumG / n).pow(invP).toFloat()
        val normB = (sumB / n).pow(invP).toFloat()
        val normAll = (normR + normG + normB) / 3f

        return WbGains(
            r = normAll / normR.coerceAtLeast(0.01f),
            g = normAll / normG.coerceAtLeast(0.01f),
            b = normAll / normB.coerceAtLeast(0.01f),
        )
    }

    /**
     * 推定ゲインの信頼度を計算。
     * ゲインが 1.0 に近い（補正量が少ない）ほど信頼度が高い。
     * 極端なゲインの推定は信頼度を下げる。
     */
    private fun computeConfidence(gains: WbGains): Float {
        val deviation = abs(gains.r - 1f) + abs(gains.g - 1f) + abs(gains.b - 1f)
        // deviation=0 → confidence=1, deviation=3 → confidence≒0
        return (1f - deviation / 3f).coerceIn(0.05f, 1f)
    }

    /**
     * 緑かぶり補正。
     * 蛍光灯下では G チャンネルが過剰になりやすい。
     * R,B の平均ゲインに対して G ゲインが著しく低い場合、
     * G を少し引き上げて自然な色合いに近づける。
     */
    private fun correctGreenTint(gains: WbGains): WbGains {
        val rbAvg = (gains.r + gains.b) / 2f
        // G ゲインが R,B 平均より15%以上低い = 緑かぶり
        return if (gains.g < rbAvg * 0.85f) {
            val correction = (rbAvg + gains.g) / 2f
            WbGains(gains.r, correction, gains.b)
        } else {
            gains
        }
    }
}
