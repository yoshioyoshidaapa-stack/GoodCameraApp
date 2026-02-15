package com.goodcamera.app.processing

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * かすみ除去フィルタ（Dehaze）
 *
 * Dark Channel Prior（暗チャネル事前分布）に基づくかすみ除去アルゴリズム。
 * He et al. (2009) "Single Image Haze Removal Using Dark Channel Prior" を
 * モバイル向けに高速化した実装。
 *
 * 原理:
 * - 屋外のかすみのない画像では、ほとんどのパッチに少なくとも1つの
 *   チャネルで非常に低い値（暗チャネル）を持つピクセルが存在する
 * - かすみがかかると暗チャネルの値が上がる（白っぽくなる）
 * - 暗チャネルから大気光 A と透過率 t(x) を推定し、元の色を復元する
 *
 * 霧化モデル: I(x) = J(x)·t(x) + A·(1 - t(x))
 * 復元: J(x) = (I(x) - A) / max(t(x), t0) + A
 */
object DehazeFilter {

    /**
     * かすみ除去を適用する
     *
     * @param bitmap 入力画像
     * @param strength 除去強度 0.0〜1.0（デフォルト0.7）
     * @param patchSize ダークチャネル計算のパッチサイズ（デフォルト7）
     */
    fun dehaze(bitmap: Bitmap, strength: Float = 0.7f, patchSize: Int = 7): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Step 1: ダークチャネルを計算
        val darkChannel = computeDarkChannel(pixels, width, height, patchSize)

        // Step 2: 大気光 A を推定（ダークチャネルの上位 0.1% から最も明るいピクセル）
        val atmosphericLight = estimateAtmosphericLight(pixels, darkChannel, width, height)

        // Step 3: 透過率マップ t(x) を推定
        val transmission = estimateTransmission(pixels, atmosphericLight, width, height, patchSize, strength)

        // Step 4: ガイデッドフィルタの代わりにボックスブラーで透過率マップを平滑化
        val refinedTransmission = smoothTransmission(transmission, width, height, patchSize * 2)

        // Step 5: シーン復元 J(x) = (I(x) - A) / max(t(x), t0) + A
        val result = recoverScene(pixels, refinedTransmission, atmosphericLight, width, height)

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * ダークチャネルを計算
     * 各パッチ内のR,G,Bの最小値の最小値
     */
    private fun computeDarkChannel(
        pixels: IntArray,
        width: Int,
        height: Int,
        patchSize: Int,
    ): IntArray {
        val halfPatch = patchSize / 2
        val darkChannel = IntArray(width * height)

        // まず各ピクセルのRGB最小値を計算
        val minChannel = IntArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            minChannel[i] = min(r, min(g, b))
        }

        // パッチ内の最小値（ミニマムフィルタ）を2パス高速計算
        // 水平パス
        val horizontalMin = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var minVal = 255
                val xStart = max(0, x - halfPatch)
                val xEnd = min(width - 1, x + halfPatch)
                for (xx in xStart..xEnd) {
                    minVal = min(minVal, minChannel[y * width + xx])
                }
                horizontalMin[y * width + x] = minVal
            }
        }

        // 垂直パス
        for (y in 0 until height) {
            for (x in 0 until width) {
                var minVal = 255
                val yStart = max(0, y - halfPatch)
                val yEnd = min(height - 1, y + halfPatch)
                for (yy in yStart..yEnd) {
                    minVal = min(minVal, horizontalMin[yy * width + x])
                }
                darkChannel[y * width + x] = minVal
            }
        }

        return darkChannel
    }

    /**
     * 大気光を推定
     * ダークチャネルの上位0.1%のピクセルから最も明るいものを選ぶ
     */
    private fun estimateAtmosphericLight(
        pixels: IntArray,
        darkChannel: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        val numPixels = width * height
        val topCount = max(numPixels / 1000, 1) // 上位 0.1%

        // ダークチャネル値でソートしたインデックスを取得（上位のみ）
        // 高速化: 全ソートの代わりにしきい値を求める
        val sorted = darkChannel.copyOf()
        sorted.sort()
        val threshold = sorted[numPixels - topCount]

        var bestIdx = 0
        var bestBrightness = 0
        for (i in 0 until numPixels) {
            if (darkChannel[i] >= threshold) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                val brightness = r + g + b
                if (brightness > bestBrightness) {
                    bestBrightness = brightness
                    bestIdx = i
                }
            }
        }

        return intArrayOf(
            (pixels[bestIdx] shr 16) and 0xFF,
            (pixels[bestIdx] shr 8) and 0xFF,
            pixels[bestIdx] and 0xFF,
        )
    }

    /**
     * 透過率マップを推定
     * t(x) = 1 - strength * min_c(I_c(y) / A_c)  (パッチ内)
     */
    private fun estimateTransmission(
        pixels: IntArray,
        atmosphericLight: IntArray,
        width: Int,
        height: Int,
        patchSize: Int,
        strength: Float,
    ): FloatArray {
        val halfPatch = patchSize / 2
        val aR = max(atmosphericLight[0], 1)
        val aG = max(atmosphericLight[1], 1)
        val aB = max(atmosphericLight[2], 1)

        // 各ピクセルのRGB / A の最小値
        val normalizedMin = FloatArray(width * height)
        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16) and 0xFF).toFloat() / aR
            val g = ((pixels[i] shr 8) and 0xFF).toFloat() / aG
            val b = (pixels[i] and 0xFF).toFloat() / aB
            normalizedMin[i] = min(r, min(g, b))
        }

        // パッチ内最小値
        val transmission = FloatArray(width * height)

        // 水平パス
        val horizMin = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var minVal = 1f
                val xStart = max(0, x - halfPatch)
                val xEnd = min(width - 1, x + halfPatch)
                for (xx in xStart..xEnd) {
                    minVal = min(minVal, normalizedMin[y * width + xx])
                }
                horizMin[y * width + x] = minVal
            }
        }

        // 垂直パス + 透過率計算
        for (y in 0 until height) {
            for (x in 0 until width) {
                var minVal = 1f
                val yStart = max(0, y - halfPatch)
                val yEnd = min(height - 1, y + halfPatch)
                for (yy in yStart..yEnd) {
                    minVal = min(minVal, horizMin[yy * width + x])
                }
                // t(x) = 1 - strength * darkChannel_normalized
                transmission[y * width + x] = 1f - strength * minVal
            }
        }

        return transmission
    }

    /**
     * 透過率マップをボックスブラーで平滑化（ガイデッドフィルタの高速近似）
     */
    private fun smoothTransmission(
        transmission: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
    ): FloatArray {
        val result = FloatArray(transmission.size)
        val temp = FloatArray(transmission.size)

        // 水平ボックスブラー
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                val xStart = max(0, x - radius)
                val xEnd = min(width - 1, x + radius)
                for (xx in xStart..xEnd) {
                    sum += transmission[y * width + xx]
                    count++
                }
                temp[y * width + x] = sum / count
            }
        }

        // 垂直ボックスブラー
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                val yStart = max(0, y - radius)
                val yEnd = min(height - 1, y + radius)
                for (yy in yStart..yEnd) {
                    sum += temp[yy * width + x]
                    count++
                }
                result[y * width + x] = sum / count
            }
        }

        return result
    }

    /**
     * シーン復元
     * J(x) = (I(x) - A) / max(t(x), t0) + A
     * t0: 最小透過率（暗部の過度な増幅を防止）
     */
    private fun recoverScene(
        pixels: IntArray,
        transmission: FloatArray,
        atmosphericLight: IntArray,
        width: Int,
        height: Int,
    ): IntArray {
        val result = IntArray(width * height)
        val t0 = 0.1f // 最小透過率
        val aR = atmosphericLight[0].toFloat()
        val aG = atmosphericLight[1].toFloat()
        val aB = atmosphericLight[2].toFloat()

        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16) and 0xFF).toFloat()
            val g = ((pixels[i] shr 8) and 0xFF).toFloat()
            val b = (pixels[i] and 0xFF).toFloat()

            val t = max(transmission[i], t0)

            val jr = ((r - aR) / t + aR).toInt().coerceIn(0, 255)
            val jg = ((g - aG) / t + aG).toInt().coerceIn(0, 255)
            val jb = ((b - aB) / t + aB).toInt().coerceIn(0, 255)

            result[i] = (0xFF shl 24) or (jr shl 16) or (jg shl 8) or jb
        }

        return result
    }

    /**
     * 画像のかすみ度合いを推定する（0.0〜1.0）
     * ダークチャネルの平均値が高いほどかすみが強い
     */
    fun estimateHazeLevel(bitmap: Bitmap): Float {
        // 高速化のためダウンサンプル
        val scale = minOf(80f / bitmap.width, 60f / bitmap.height, 1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        // ダークチャネルの平均を計算
        var darkSum = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            darkSum += min(r, min(g, b))
        }

        val avgDark = darkSum.toFloat() / pixels.size
        // 暗チャネル平均値を0〜1にマッピング（100以上で最大かすみ）
        return (avgDark / 100f).coerceIn(0f, 1f)
    }
}
