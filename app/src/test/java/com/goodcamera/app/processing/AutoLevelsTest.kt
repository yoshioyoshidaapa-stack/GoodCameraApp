package com.goodcamera.app.processing

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AutoLevelsTest {

    private lateinit var testBitmap: Bitmap

    @Before
    fun setUp() {
        // 4x4のテスト画像を作成（低コントラスト: 値が100-150の範囲に集中）
        testBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(16)
        for (i in pixels.indices) {
            val value = 100 + (i * 3) // 100〜145
            pixels[i] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        testBitmap.setPixels(pixels, 0, 4, 0, 0, 4, 4)
    }

    @Test
    fun autoAdjust_expandsContrastRange() {
        val result = AutoLevels.autoAdjust(testBitmap, clipPercent = 0f)

        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        // 出力の最小値と最大値を確認
        var minVal = 255
        var maxVal = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            if (r < minVal) minVal = r
            if (r > maxVal) maxVal = r
        }

        // コントラストが拡張されているはず（元は100-145の範囲）
        assertTrue("Min should be close to 0, was $minVal", minVal < 30)
        assertTrue("Max should be close to 255, was $maxVal", maxVal > 200)
        result.recycle()
    }

    @Test
    fun autoContrast_preservesColorRatios() {
        // 色付きの画像でテスト（R=200, G=100, B=50）
        val colorBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val pixels = intArrayOf(
            (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50,
            (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50,
            (0xFF shl 24) or (100 shl 16) or (50 shl 8) or 25,
            (0xFF shl 24) or (100 shl 16) or (50 shl 8) or 25,
        )
        colorBitmap.setPixels(pixels, 0, 2, 0, 0, 2, 2)

        val result = AutoLevels.autoContrast(colorBitmap, clipPercent = 0f)
        val resultPixels = IntArray(4)
        result.getPixels(resultPixels, 0, 2, 0, 0, 2, 2)

        // R:G:B の比率がおおよそ維持されるか確認
        val r = (resultPixels[0] shr 16) and 0xFF
        val g = (resultPixels[0] shr 8) and 0xFF
        val b = resultPixels[0] and 0xFF

        // R > G > B の順序が保たれている
        assertTrue("R($r) should be > G($g)", r > g)
        assertTrue("G($g) should be > B($b)", g > b)

        colorBitmap.recycle()
        result.recycle()
    }

    @Test
    fun autoAdjust_handlesUniformImage() {
        // 全ピクセルが同じ色（コントラスト0の画像）
        val uniformBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(16) { (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128 }
        uniformBitmap.setPixels(pixels, 0, 4, 0, 0, 4, 4)

        // 例外なく処理が完了すること
        val result = AutoLevels.autoAdjust(uniformBitmap, clipPercent = 1f)
        assertEquals(4, result.width)
        assertEquals(4, result.height)

        uniformBitmap.recycle()
        result.recycle()
    }

    @Test
    fun autoContrast_handlesBlackImage() {
        val blackBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(16) { (0xFF shl 24) or 0 }
        blackBitmap.setPixels(pixels, 0, 4, 0, 0, 4, 4)

        val result = AutoLevels.autoContrast(blackBitmap, clipPercent = 0.5f)
        assertEquals(4, result.width)

        blackBitmap.recycle()
        result.recycle()
    }

    @Test
    fun autoAdjust_outputSameDimensions() {
        val result = AutoLevels.autoAdjust(testBitmap, clipPercent = 1f)
        assertEquals(testBitmap.width, result.width)
        assertEquals(testBitmap.height, result.height)
        result.recycle()
    }
}
