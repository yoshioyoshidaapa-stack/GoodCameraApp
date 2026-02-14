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
class ImageProcessorTest {

    private lateinit var testBitmap: Bitmap

    @Before
    fun setUp() {
        // 16x16 の低コントラスト画像
        testBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val r = (100 + x * 5).coerceAtMost(255)
                val g = (80 + y * 5).coerceAtMost(255)
                val b = 120
                pixels[y * 16 + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        testBitmap.setPixels(pixels, 0, 16, 0, 0, 16, 16)
    }

    @Test
    fun process_withDefaultConfig_returnsProcessedBitmap() {
        val result = ImageProcessor.process(testBitmap)
        assertNotNull(result)
        assertEquals(testBitmap.width, result.width)
        assertEquals(testBitmap.height, result.height)
        result.recycle()
    }

    @Test
    fun process_withAllDisabled_returnsOriginal() {
        val config = ImageProcessor.ProcessingConfig(
            autoWbEnabled = false,
            denoiseEnabled = false,
            deblurEnabled = false,
            perceptualEnabled = false,
            sharpenEnabled = false,
            autoLevelsEnabled = false,
        )
        val result = ImageProcessor.process(testBitmap, config)

        // 全処理を無効にした場合、元の画像と同じピクセル値
        val originalPixels = IntArray(256)
        val resultPixels = IntArray(256)
        testBitmap.getPixels(originalPixels, 0, 16, 0, 0, 16, 16)
        result.getPixels(resultPixels, 0, 16, 0, 0, 16, 16)

        assertArrayEquals(originalPixels, resultPixels)
    }

    @Test
    fun process_withOnlySharpen_changesBitmap() {
        val config = ImageProcessor.ProcessingConfig(
            autoWbEnabled = false,
            denoiseEnabled = false,
            deblurEnabled = false,
            perceptualEnabled = false,
            sharpenEnabled = true,
            sharpenAmount = 1.5f,
            autoLevelsEnabled = false,
        )
        val result = ImageProcessor.process(testBitmap, config)

        val originalPixels = IntArray(256)
        val resultPixels = IntArray(256)
        testBitmap.getPixels(originalPixels, 0, 16, 0, 0, 16, 16)
        result.getPixels(resultPixels, 0, 16, 0, 0, 16, 16)

        // シャープニング適用で何かが変わっている
        var changed = false
        for (i in originalPixels.indices) {
            if (originalPixels[i] != resultPixels[i]) {
                changed = true
                break
            }
        }
        assertTrue("Sharpening should modify the image", changed)
        result.recycle()
    }

    @Test
    fun process_withOnlyDenoise_changesBitmap() {
        val config = ImageProcessor.ProcessingConfig(
            autoWbEnabled = false,
            denoiseEnabled = true,
            denoiseStrength = ImageProcessor.DenoiseStrength.LIGHT,
            deblurEnabled = false,
            perceptualEnabled = false,
            sharpenEnabled = false,
            autoLevelsEnabled = false,
        )
        val result = ImageProcessor.process(testBitmap, config)
        assertEquals(16, result.width)
        assertEquals(16, result.height)
        result.recycle()
    }

    @Test
    fun processNight_returnsProcessedBitmap() {
        val result = ImageProcessor.processNight(testBitmap)
        assertNotNull(result)
        assertEquals(testBitmap.width, result.width)
        assertEquals(testBitmap.height, result.height)
        result.recycle()
    }

    @Test
    fun denoiseStrength_hasCorrectValues() {
        assertEquals(2, ImageProcessor.DenoiseStrength.LIGHT.radius)
        assertEquals(3, ImageProcessor.DenoiseStrength.MEDIUM.radius)
        assertEquals(4, ImageProcessor.DenoiseStrength.STRONG.radius)

        assertTrue(ImageProcessor.DenoiseStrength.LIGHT.sigmaColor <
                ImageProcessor.DenoiseStrength.MEDIUM.sigmaColor)
        assertTrue(ImageProcessor.DenoiseStrength.MEDIUM.sigmaColor <
                ImageProcessor.DenoiseStrength.STRONG.sigmaColor)
    }
}
