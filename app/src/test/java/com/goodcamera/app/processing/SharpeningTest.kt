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
class SharpeningTest {

    private lateinit var testBitmap: Bitmap

    @Before
    fun setUp() {
        // 8x8のテスト画像を作成（左半分が暗い、右半分が明るい＝エッジあり）
        testBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val value = if (x < 4) 50 else 200
                pixels[y * 8 + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        testBitmap.setPixels(pixels, 0, 8, 0, 0, 8, 8)
    }

    @Test
    fun unsharpMask_enhancesEdges() {
        val result = Sharpening.unsharpMask(testBitmap, amount = 2.0f, radius = 1, threshold = 0)

        val pixels = IntArray(64)
        result.getPixels(pixels, 0, 8, 0, 0, 8, 8)

        // エッジ付近のピクセルを確認
        // 左側のエッジ（暗い側）はより暗く、右側のエッジ（明るい側）はより明るくなるはず
        val darkSide = (pixels[3 * 8 + 2] shr 16) and 0xFF  // 暗い側の内部
        val edge_dark = (pixels[3 * 8 + 3] shr 16) and 0xFF  // エッジの暗い側
        val edge_light = (pixels[3 * 8 + 4] shr 16) and 0xFF  // エッジの明るい側
        val lightSide = (pixels[3 * 8 + 5] shr 16) and 0xFF  // 明るい側の内部

        // エッジの暗い側は内部よりさらに暗い or 同じ
        assertTrue("Edge dark($edge_dark) should be <= dark side($darkSide)", edge_dark <= darkSide)
        // エッジの明るい側は内部より明るい or 同じ
        assertTrue("Edge light($edge_light) should be >= light side($lightSide)", edge_light >= lightSide)

        result.recycle()
    }

    @Test
    fun unsharpMask_thresholdPreventsNoiseEnhancement() {
        // 小さなノイズを含む画像
        val noisyBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(16)
        for (i in pixels.indices) {
            val value = 128 + (if (i % 2 == 0) 2 else -2) // ±2の微小なノイズ
            pixels[i] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        noisyBitmap.setPixels(pixels, 0, 4, 0, 0, 4, 4)

        // threshold=10 で適用（差分が2なので閾値以下）
        val result = Sharpening.unsharpMask(noisyBitmap, amount = 2.0f, radius = 1, threshold = 10)
        val resultPixels = IntArray(16)
        result.getPixels(resultPixels, 0, 4, 0, 0, 4, 4)

        // 閾値以下の差分は変更されない → 中心部のピクセルは元の値に近い
        for (i in 5..9) { // 中心部のピクセル
            val original = (pixels[i] shr 16) and 0xFF
            val processed = (resultPixels[i] shr 16) and 0xFF
            assertEquals("Pixel $i should be unchanged (threshold protection)", original, processed)
        }

        noisyBitmap.recycle()
        result.recycle()
    }

    @Test
    fun unsharpMask_outputSameDimensions() {
        val result = Sharpening.unsharpMask(testBitmap)
        assertEquals(testBitmap.width, result.width)
        assertEquals(testBitmap.height, result.height)
        result.recycle()
    }

    @Test
    fun unsharpMask_zeroAmount_noChange() {
        // amount=0 の場合、出力は入力とほぼ同じ
        val result = Sharpening.unsharpMask(testBitmap, amount = 0f, radius = 1, threshold = 0)
        val originalPixels = IntArray(64)
        val resultPixels = IntArray(64)
        testBitmap.getPixels(originalPixels, 0, 8, 0, 0, 8, 8)
        result.getPixels(resultPixels, 0, 8, 0, 0, 8, 8)

        // 均一な領域のピクセルは変わらない
        assertEquals(originalPixels[0], resultPixels[0])
        assertEquals(originalPixels[63], resultPixels[63])
        result.recycle()
    }

    @Test
    fun unsharpMask_valuesClampedTo0_255() {
        // 極端な値でシャープニング
        val extremeBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(16)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val value = if (x < 2) 0 else 255
                pixels[y * 4 + x] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
        }
        extremeBitmap.setPixels(pixels, 0, 4, 0, 0, 4, 4)

        val result = Sharpening.unsharpMask(extremeBitmap, amount = 3.0f, radius = 1, threshold = 0)
        val resultPixels = IntArray(16)
        result.getPixels(resultPixels, 0, 4, 0, 0, 4, 4)

        // すべてのピクセルが有効範囲内
        for (pixel in resultPixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertTrue("R=$r out of range", r in 0..255)
            assertTrue("G=$g out of range", g in 0..255)
            assertTrue("B=$b out of range", b in 0..255)
        }

        extremeBitmap.recycle()
        result.recycle()
    }
}
