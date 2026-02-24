package com.goodcamera.app.processing

import android.graphics.Bitmap

/**
 * 複数フレームの平均化によるノイズ低減
 *
 * N枚のフレームをピクセル単位で平均化する。
 * ランダムノイズが 1/sqrt(N) に低減され、暗所撮影の画質が向上する。
 */
object FrameStacker {

    /**
     * フレームをピクセル単位で平均化してスタック
     */
    fun stack(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val width = frames[0].width
        val height = frames[0].height
        val pixelCount = width * height
        val frameCount = frames.size

        // 各チャネルの累積値（intで十分: 255 * 16 = 4080 < Int.MAX）
        val sumR = IntArray(pixelCount)
        val sumG = IntArray(pixelCount)
        val sumB = IntArray(pixelCount)

        val pixels = IntArray(pixelCount)

        for (frame in frames) {
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                sumR[i] += (pixels[i] shr 16) and 0xFF
                sumG[i] += (pixels[i] shr 8) and 0xFF
                sumB[i] += pixels[i] and 0xFF
            }
        }

        val result = IntArray(pixelCount)
        for (i in 0 until pixelCount) {
            val r = (sumR[i] / frameCount).coerceIn(0, 255)
            val g = (sumG[i] / frameCount).coerceIn(0, 255)
            val b = (sumB[i] / frameCount).coerceIn(0, 255)
            result[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(result, 0, width, 0, 0, width, height)
        return output
    }
}
