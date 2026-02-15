package com.goodcamera.app.processing

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SceneDetectorTest {

    @Test
    fun analyze_darkScene_detectsNightScene() {
        // 暗い画像（輝度20前後）
        val bitmap = createUniformBitmap(20, 20, 25)
        val analysis = SceneDetector.analyze(bitmap)

        assertEquals(SceneDetector.SceneType.NIGHT_SCENE, analysis.sceneType)
        assertTrue("Brightness should be low", analysis.avgBrightness < 50f)
        assertTrue("Should be low light", analysis.isLowLight)
        bitmap.recycle()
    }

    @Test
    fun analyze_greenScene_detectsLandscape() {
        // 緑が多い画像（風景）
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(400)
        for (i in pixels.indices) {
            // 緑優位: R=40, G=120, B=50
            pixels[i] = (0xFF shl 24) or (40 shl 16) or (120 shl 8) or 50
        }
        bitmap.setPixels(pixels, 0, 20, 0, 0, 20, 20)

        val analysis = SceneDetector.analyze(bitmap)
        assertEquals(SceneDetector.SceneType.LANDSCAPE, analysis.sceneType)
        assertTrue("Green ratio should be high", analysis.greenRatio > 0.5f)
        bitmap.recycle()
    }

    @Test
    fun analyze_skinTones_detectsPortrait() {
        // 肌色が多い画像（ポートレート）
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(400)
        for (i in pixels.indices) {
            // 典型的な肌色: R=200, G=160, B=130
            pixels[i] = (0xFF shl 24) or (200 shl 16) or (160 shl 8) or 130
        }
        bitmap.setPixels(pixels, 0, 20, 0, 0, 20, 20)

        val analysis = SceneDetector.analyze(bitmap)
        assertEquals(SceneDetector.SceneType.PORTRAIT, analysis.sceneType)
        assertTrue("Skin ratio should be high", analysis.skinRatio > 0.1f)
        bitmap.recycle()
    }

    @Test
    fun analyze_warmColors_detectsFood() {
        // 暖色が多い画像（料理）
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(400)
        for (i in pixels.indices) {
            // 暖色: R=220, G=140, B=60 （オレンジ系）
            pixels[i] = (0xFF shl 24) or (220 shl 16) or (140 shl 8) or 60
        }
        bitmap.setPixels(pixels, 0, 20, 0, 0, 20, 20)

        val analysis = SceneDetector.analyze(bitmap)
        assertEquals(SceneDetector.SceneType.FOOD, analysis.sceneType)
        assertTrue("Warm ratio should be high", analysis.warmRatio > 0.3f)
        bitmap.recycle()
    }

    @Test
    fun recommendSettings_nightScene_usesNightMode() {
        val analysis = SceneDetector.SceneAnalysis(
            sceneType = SceneDetector.SceneType.NIGHT_SCENE,
            confidence = 0.9f,
            avgBrightness = 25f,
            dynamicRange = 100f,
            edgeDensity = 0.1f,
            skinRatio = 0f,
            greenRatio = 0f,
            warmRatio = 0f,
            isLowLight = true,
            isHighContrast = false,
        )

        val recommendation = SceneDetector.recommendSettings(analysis)
        assertTrue("Night scene should use night mode", recommendation.useNightMode)
        assertFalse("Night scene should not use HDR", recommendation.useHdr)
        assertEquals(ImageProcessor.DenoiseStrength.STRONG, recommendation.processingConfig.denoiseStrength)
    }

    @Test
    fun recommendSettings_hdr_usesHdrMode() {
        val analysis = SceneDetector.SceneAnalysis(
            sceneType = SceneDetector.SceneType.HIGH_DYNAMIC_RANGE,
            confidence = 0.85f,
            avgBrightness = 120f,
            dynamicRange = 220f,
            edgeDensity = 0.2f,
            skinRatio = 0f,
            greenRatio = 0.1f,
            warmRatio = 0.1f,
            isLowLight = false,
            isHighContrast = true,
        )

        val recommendation = SceneDetector.recommendSettings(analysis)
        assertTrue("HDR scene should use HDR", recommendation.useHdr)
        assertFalse("HDR scene should not use night mode", recommendation.useNightMode)
    }

    @Test
    fun recommendSettings_document_highSharpen() {
        val analysis = SceneDetector.SceneAnalysis(
            sceneType = SceneDetector.SceneType.DOCUMENT,
            confidence = 0.85f,
            avgBrightness = 180f,
            dynamicRange = 200f,
            edgeDensity = 0.4f,
            skinRatio = 0f,
            greenRatio = 0f,
            warmRatio = 0f,
            isLowLight = false,
            isHighContrast = true,
        )

        val recommendation = SceneDetector.recommendSettings(analysis)
        assertTrue("Document should have high sharpen", recommendation.processingConfig.sharpenAmount >= 1.5f)
        assertFalse("Document should not use perceptual", recommendation.processingConfig.perceptualEnabled)
    }

    @Test
    fun analyze_returnsValidConfidence() {
        val bitmap = createUniformBitmap(128, 128, 128)
        val analysis = SceneDetector.analyze(bitmap)
        assertTrue("Confidence should be >= 0", analysis.confidence >= 0f)
        assertTrue("Confidence should be <= 1", analysis.confidence <= 1f)
        bitmap.recycle()
    }

    private fun createUniformBitmap(r: Int, g: Int, b: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(400)
        for (i in pixels.indices) {
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, 20, 0, 0, 20, 20)
        return bitmap
    }
}
