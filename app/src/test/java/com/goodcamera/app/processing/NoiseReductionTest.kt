package com.goodcamera.app.processing

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NoiseReductionTest {

    @Test
    fun bilateralFilter_reducesNoise() {
        val width = 8
        val height = 8
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val baseValue = 128

        // ランダムノイズを加えた画像
        for (i in pixels.indices) {
            val noise = ((i * 37) % 40) - 20 // -20〜+19のノイズ
            val value = (baseValue + noise).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val result = NoiseReduction.bilateralFilter(bitmap, radius = 2, sigmaSpace = 8f, sigmaColor = 20f)
        val resultPixels = IntArray(width * height)
        result.getPixels(resultPixels, 0, width, 0, 0, width, height)

        // ノイズ除去後の分散が小さくなっていることを確認
        val originalVariance = computeVariance(pixels)
        val resultVariance = computeVariance(resultPixels)

        assertTrue(
            "Variance should decrease: original=$originalVariance, result=$resultVariance",
            resultVariance < originalVariance,
        )

        bitmap.recycle()
        result.recycle()
    }

    @Test
    fun bilateralFilter_preservesEdges() {
        val width = 8
        val height = 8
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // 左半分が暗い、右半分が明るい（強いエッジ）
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (x < 4) 40 else 220
                pixels[y * width + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val result = NoiseReduction.bilateralFilter(bitmap, radius = 2, sigmaSpace = 10f, sigmaColor = 30f)
        val resultPixels = IntArray(width * height)
        result.getPixels(resultPixels, 0, width, 0, 0, width, height)

        // エッジの両側がそれぞれの領域に近い値を保つ
        val leftCenter = (resultPixels[3 * width + 1] shr 16) and 0xFF
        val rightCenter = (resultPixels[3 * width + 6] shr 16) and 0xFF

        assertTrue("Left side($leftCenter) should be dark (<100)", leftCenter < 100)
        assertTrue("Right side($rightCenter) should be bright (>150)", rightCenter > 150)

        bitmap.recycle()
        result.recycle()
    }

    @Test
    fun fastDenoise_reducesNoise() {
        val width = 6
        val height = 6
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val baseValue = 128

        for (i in pixels.indices) {
            val noise = ((i * 31) % 30) - 15
            val value = (baseValue + noise).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val result = NoiseReduction.fastDenoise(bitmap, strength = 0.5f)
        assertEquals(width, result.width)
        assertEquals(height, result.height)

        bitmap.recycle()
        result.recycle()
    }

    @Test
    fun bilateralFilter_outputSameDimensions() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = NoiseReduction.bilateralFilter(bitmap)
        assertEquals(10, result.width)
        assertEquals(10, result.height)
        bitmap.recycle()
        result.recycle()
    }

    private fun computeVariance(pixels: IntArray): Double {
        val values = pixels.map { ((it shr 16) and 0xFF).toDouble() }
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
}
