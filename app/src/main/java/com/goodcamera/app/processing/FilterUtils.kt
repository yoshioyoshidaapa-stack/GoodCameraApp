package com.goodcamera.app.processing

import kotlin.math.max
import kotlin.math.min

/**
 * 画像処理フィルタの共通ユーティリティ
 */
internal object FilterUtils {

    /**
     * 2パス分離ボックスブラー（ガウシアン近似）
     * 水平→垂直の2パスで高速にぼかし処理を行う。
     *
     * @param pixels ARGB packed int 配列
     * @param width 画像幅
     * @param height 画像高さ
     * @param radius ぼかし半径
     */
    fun boxBlur2Pass(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val temp = IntArray(pixels.size)
        val result = IntArray(pixels.size)
        val kernelSize = 2 * radius + 1

        // 水平パス
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var sumR = 0; var sumG = 0; var sumB = 0
                val xStart = max(0, x - radius)
                val xEnd = min(width - 1, x + radius)
                val count = xEnd - xStart + 1
                for (xx in xStart..xEnd) {
                    val idx = rowOffset + xx
                    sumR += (pixels[idx] shr 16) and 0xFF
                    sumG += (pixels[idx] shr 8) and 0xFF
                    sumB += pixels[idx] and 0xFF
                }
                temp[rowOffset + x] = (0xFF shl 24) or
                        ((sumR / count) shl 16) or
                        ((sumG / count) shl 8) or
                        (sumB / count)
            }
        }

        // 垂直パス
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sumR = 0; var sumG = 0; var sumB = 0
                val yStart = max(0, y - radius)
                val yEnd = min(height - 1, y + radius)
                val count = yEnd - yStart + 1
                for (yy in yStart..yEnd) {
                    val idx = yy * width + x
                    sumR += (temp[idx] shr 16) and 0xFF
                    sumG += (temp[idx] shr 8) and 0xFF
                    sumB += temp[idx] and 0xFF
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
