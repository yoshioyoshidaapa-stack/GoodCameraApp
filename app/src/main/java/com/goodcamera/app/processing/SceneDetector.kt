package com.goodcamera.app.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AIシーン自動検出エンジン
 *
 * プレビューフレームを解析し、以下の特徴量から最適なシーンを推定する:
 * - 平均輝度・輝度分布 → 暗所/明所判定
 * - ダイナミックレンジ → HDR必要性判定
 * - エッジ密度 → ディテール量判定
 * - 色温度・色相分布 → 屋内/屋外・シーン判定
 * - 肌色検出 → ポートレート判定
 * - 緑/青比率 → 風景判定
 * - 暖色比率 → 料理判定
 * - マクロ判定 → 近接撮影検出
 */
object SceneDetector {

    enum class SceneType(val label: String, val icon: String) {
        PORTRAIT("ポートレート", "person"),
        LANDSCAPE("風景", "landscape"),
        NIGHT_SCENE("夜景", "nightlight"),
        FOOD("料理", "restaurant"),
        DOCUMENT("文書", "description"),
        MACRO("マクロ", "macro_off"),
        HIGH_DYNAMIC_RANGE("HDR", "hdr_on"),
        BACKLIGHT("逆光", "wb_sunny"),
        GENERAL("標準", "auto_awesome"),
    }

    data class SceneAnalysis(
        val sceneType: SceneType,
        val confidence: Float,
        val avgBrightness: Float,
        val dynamicRange: Float,
        val edgeDensity: Float,
        val skinRatio: Float,
        val greenRatio: Float,
        val warmRatio: Float,
        val isLowLight: Boolean,
        val isHighContrast: Boolean,
        val hazeLevel: Float = 0f,
    )

    /**
     * プレビューフレームからシーンを解析
     * 計算コスト削減のため、画像をダウンサンプリングして解析する
     */
    fun analyze(bitmap: Bitmap): SceneAnalysis {
        // ダウンサンプリング（最大80x60で解析）
        val scale = minOf(80f / bitmap.width, 60f / bitmap.height, 1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        // 全特徴量を1パスで計算
        val features = analyzeAllFeatures(pixels, w, h)
        val brightness = features.brightness
        val dynamicRange = features.dynamicRange
        val edgeDensity = features.edgeDensity
        val skinRatio = features.skinRatio
        val greenRatio = features.greenRatio
        val warmRatio = features.warmRatio
        val blueRatio = features.blueRatio
        val saturation = features.saturation
        val hazeLevel = features.hazeLevel
        val isLowLight = brightness < 50f
        val isHighContrast = dynamicRange > 180f

        // シーン推定（各シーンのスコアを計算し、最高スコアを採用）
        val scores = mutableMapOf<SceneType, Float>()

        // 夜景: 暗い＋ある程度のエッジ
        scores[SceneType.NIGHT_SCENE] = when {
            brightness < 30f -> 0.9f
            brightness < 50f -> 0.6f
            brightness < 70f -> 0.2f
            else -> 0f
        }

        // ポートレート: 肌色率が高い
        scores[SceneType.PORTRAIT] = when {
            skinRatio > 0.15f -> 0.9f
            skinRatio > 0.08f -> 0.6f
            skinRatio > 0.04f -> 0.3f
            else -> 0f
        }

        // 風景: 緑や青が多い + エッジ密度中程度
        scores[SceneType.LANDSCAPE] = when {
            greenRatio > 0.3f || blueRatio > 0.25f -> 0.8f
            greenRatio > 0.2f || blueRatio > 0.15f -> 0.5f
            greenRatio > 0.1f -> 0.2f
            else -> 0f
        }

        // 料理: 暖色が多い + 中程度の彩度
        scores[SceneType.FOOD] = when {
            warmRatio > 0.35f && saturation > 40f -> 0.85f
            warmRatio > 0.25f && saturation > 30f -> 0.5f
            warmRatio > 0.15f -> 0.2f
            else -> 0f
        }

        // 文書: 彩度が低い + 高コントラスト + エッジ密度高い
        scores[SceneType.DOCUMENT] = when {
            saturation < 15f && edgeDensity > 0.3f -> 0.85f
            saturation < 25f && edgeDensity > 0.25f -> 0.5f
            else -> 0f
        }

        // HDR: ダイナミックレンジが非常に広い（逆光含む）
        scores[SceneType.HIGH_DYNAMIC_RANGE] = when {
            dynamicRange > 200f && brightness > 60f -> 0.85f
            dynamicRange > 180f -> 0.5f
            else -> 0f
        }

        // 逆光: 画面の上下で輝度差が大きい
        val backlightScore = analyzeBacklight(pixels, w, h)
        scores[SceneType.BACKLIGHT] = backlightScore

        // マクロ: 中央のエッジ密度が高く、周辺がぼけている
        val macroScore = analyzeMacroPattern(pixels, w, h)
        scores[SceneType.MACRO] = macroScore

        // 標準: 他のどれにも強く当てはまらない場合のベースライン
        scores[SceneType.GENERAL] = 0.25f

        // 最高スコアのシーンを選択
        val bestEntry = scores.maxByOrNull { it.value } ?: (SceneType.GENERAL to 0.25f)
        val bestScene = bestEntry.key
        val bestConfidence = bestEntry.value

        return SceneAnalysis(
            sceneType = bestScene,
            confidence = bestConfidence,
            avgBrightness = brightness,
            dynamicRange = dynamicRange,
            edgeDensity = edgeDensity,
            skinRatio = skinRatio,
            greenRatio = greenRatio,
            warmRatio = warmRatio,
            isLowLight = isLowLight,
            isHighContrast = isHighContrast,
            hazeLevel = hazeLevel,
        )
    }

    /**
     * シーンに応じた最適な撮影設定を返す
     */
    fun recommendSettings(analysis: SceneAnalysis): SceneRecommendation {
        return when (analysis.sceneType) {
            SceneType.NIGHT_SCENE -> SceneRecommendation(
                useHdr = false,
                useNightMode = true,
                exposureCompensation = 1,
                processingConfig = ImageProcessor.ProcessingConfig(
                    denoiseEnabled = true,
                    denoiseStrength = ImageProcessor.DenoiseStrength.STRONG,
                    sharpenEnabled = true,
                    sharpenAmount = 1.5f,
                    autoLevelsEnabled = true,
                    autoLevelsClip = 0.3f,
                    perceptualEnabled = true,
                ),
            )
            SceneType.PORTRAIT -> SceneRecommendation(
                useHdr = false,
                useNightMode = false,
                exposureCompensation = 0,
                processingConfig = ImageProcessor.ProcessingConfig(
                    denoiseEnabled = true,
                    denoiseStrength = ImageProcessor.DenoiseStrength.MEDIUM,
                    sharpenEnabled = true,
                    sharpenAmount = 0.8f, // ポートレートはシャープすぎない
                    autoLevelsEnabled = true,
                    autoLevelsClip = 0.8f,
                    perceptualEnabled = true,
                    deblurEnabled = true,
                ),
            )
            SceneType.LANDSCAPE -> SceneRecommendation(
                useHdr = analysis.dynamicRange > 150f,
                useNightMode = false,
                exposureCompensation = 0,
                processingConfig = ImageProcessor.ProcessingConfig(
                    denoiseEnabled = true,
                    denoiseStrength = ImageProcessor.DenoiseStrength.LIGHT,
                    sharpenEnabled = true,
                    sharpenAmount = 1.4f, // 風景はシャープに
                    autoLevelsEnabled = true,
                    autoLevelsClip = 1.0f,
                    perceptualEnabled = true,
                    // かすみ度0.3以上で自動有効、強度はかすみ度に比例
                    dehazeEnabled = analysis.hazeLevel > 0.3f,
                    dehazeStrength = (analysis.hazeLevel * 1.0f).coerceIn(0.3f, 0.9f),
                ),
            )
            SceneType.FOOD -> SceneRecommendation(
                useHdr = false,
                useNightMode = false,
                exposureCompensation = 1, // やや明るめ
                processingConfig = ImageProcessor.ProcessingConfig(
                    denoiseEnabled = true,
                    denoiseStrength = ImageProcessor.DenoiseStrength.LIGHT,
                    sharpenEnabled = true,
                    sharpenAmount = 1.0f,
                    autoLevelsEnabled = true,
                    autoLevelsClip = 0.8f,
                    perceptualEnabled = true, // 色を鮮やかに
                ),
            )
            SceneType.DOCUMENT -> SceneRecommendation(
                useHdr = false,
                useNightMode = false,
                exposureCompensation = 1,
                processingConfig = ImageProcessor.ProcessingConfig(
                    denoiseEnabled = true,
                    denoiseStrength = ImageProcessor.DenoiseStrength.LIGHT,
                    sharpenEnabled = true,
                    sharpenAmount = 1.8f, // 文字をくっきり
                    autoLevelsEnabled = true,
                    autoLevelsClip = 2.0f, // 高コントラスト
                    perceptualEnabled = false,
                    deblurEnabled = true,
                ),
            )
            SceneType.MACRO -> SceneRecommendation(
                useHdr = false,
                useNightMode = false,
                exposureCompensation = 0,
                processingConfig = ImageProcessor.ProcessingConfig(
                    denoiseEnabled = true,
                    denoiseStrength = ImageProcessor.DenoiseStrength.MEDIUM,
                    sharpenEnabled = true,
                    sharpenAmount = 1.3f,
                    autoLevelsEnabled = true,
                    perceptualEnabled = true,
                ),
            )
            SceneType.HIGH_DYNAMIC_RANGE, SceneType.BACKLIGHT -> SceneRecommendation(
                useHdr = true,
                useNightMode = false,
                exposureCompensation = 0,
                processingConfig = ImageProcessor.ProcessingConfig(
                    denoiseEnabled = true,
                    denoiseStrength = ImageProcessor.DenoiseStrength.LIGHT,
                    sharpenEnabled = true,
                    sharpenAmount = 0.8f,
                    autoLevelsEnabled = false, // HDRが処理済み
                    perceptualEnabled = true,
                ),
            )
            SceneType.GENERAL -> SceneRecommendation(
                useHdr = false,
                useNightMode = false,
                exposureCompensation = 0,
                processingConfig = ImageProcessor.ProcessingConfig(), // デフォルト
            )
        }
    }

    data class SceneRecommendation(
        val useHdr: Boolean,
        val useNightMode: Boolean,
        val exposureCompensation: Int,
        val processingConfig: ImageProcessor.ProcessingConfig,
    )

    // ---- 特徴量計算 ----

    /**
     * 全特徴量を1パスで計算する統合メソッド
     * 旧実装では8回ピクセル配列を走査していたが、1回で全て計算する
     */
    private data class FeatureSet(
        val brightness: Float,
        val dynamicRange: Float,
        val edgeDensity: Float,
        val skinRatio: Float,
        val greenRatio: Float,
        val blueRatio: Float,
        val warmRatio: Float,
        val saturation: Float,
        val hazeLevel: Float,
    )

    private fun analyzeAllFeatures(pixels: IntArray, w: Int, h: Int): FeatureSet {
        val n = pixels.size
        var lumSum = 0L
        var minL = 255
        var maxL = 0
        var skinCount = 0
        var greenCount = 0
        var blueCount = 0
        var warmCount = 0
        var sumSat = 0f
        var darkSum = 0L

        // 輝度キャッシュ（エッジ密度計算で再利用）
        val lum = IntArray(n)

        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF

            // 輝度（整数近似: 77*R + 150*G + 29*B >> 8 ≒ 0.299R+0.587G+0.114B）
            val l = (77 * r + 150 * g + 29 * b) shr 8
            lum[i] = l

            lumSum += l
            if (l < minL) minL = l
            if (l > maxL) maxL = l

            // 肌色
            if (r > g && g > b && r >= 60 && g >= 40) {
                val rgDiff = r - g
                val rbDiff = r - b
                if (rgDiff in 10..80 && rbDiff in 20..120 && r > 80) skinCount++
            }

            // 緑
            if (g > r + 10 && g > b + 10 && g > 60) greenCount++

            // 青
            if (b > r + 15 && b > g + 5 && b > 80) blueCount++

            // 暖色
            if (r > b + 30 && r > 80 && g > 40) warmCount++

            // 彩度
            val chMax = maxOf(r, g, b)
            val chMin = minOf(r, g, b)
            if (chMax > 0) sumSat += (chMax - chMin).toFloat() / chMax * 100f

            // ダークチャネル
            darkSum += chMin
        }

        // エッジ密度（事前計算した輝度配列を使用）
        var edgeCount = 0
        val edgeThreshold = 30
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val c = lum[idx]
                if (abs(c - lum[idx + 1]) > edgeThreshold || abs(c - lum[idx + w]) > edgeThreshold) {
                    edgeCount++
                }
            }
        }
        val edgeArea = ((w - 2) * (h - 2)).coerceAtLeast(1)

        return FeatureSet(
            brightness = lumSum.toFloat() / n,
            dynamicRange = (maxL - minL).toFloat(),
            edgeDensity = edgeCount.toFloat() / edgeArea,
            skinRatio = skinCount.toFloat() / n,
            greenRatio = greenCount.toFloat() / n,
            blueRatio = blueCount.toFloat() / n,
            warmRatio = warmCount.toFloat() / n,
            saturation = sumSat / n,
            hazeLevel = (darkSum.toFloat() / n / 100f).coerceIn(0f, 1f),
        )
    }

    /**
     * 逆光検出: 画面上部が明るく下部が暗い
     */
    private fun analyzeBacklight(pixels: IntArray, w: Int, h: Int): Float {
        val topThird = h / 3
        val bottomThird = h * 2 / 3

        var topBrightness = 0f
        var bottomBrightness = 0f
        var topCount = 0
        var bottomCount = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val l = luminance(pixels[y * w + x]).toFloat()
                if (y < topThird) {
                    topBrightness += l
                    topCount++
                } else if (y >= bottomThird) {
                    bottomBrightness += l
                    bottomCount++
                }
            }
        }

        if (topCount == 0 || bottomCount == 0) return 0f
        topBrightness /= topCount
        bottomBrightness /= bottomCount

        val diff = topBrightness - bottomBrightness
        return when {
            diff > 80f -> 0.85f
            diff > 50f -> 0.5f
            diff > 30f -> 0.2f
            else -> 0f
        }
    }

    /**
     * マクロパターン検出: 中央が鮮明で周辺がぼけている
     */
    private fun analyzeMacroPattern(pixels: IntArray, w: Int, h: Int): Float {
        val cx = w / 2
        val cy = h / 2
        val regionSize = minOf(w, h) / 4

        var centerEdge = 0
        var peripheralEdge = 0
        var centerCount = 0
        var peripheralCount = 0
        val threshold = 25

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val c = luminance(pixels[idx])
                val r = luminance(pixels[idx + 1])
                val d = luminance(pixels[idx + w])
                val isEdge = abs(c - r) > threshold || abs(c - d) > threshold

                val distFromCenter = sqrt(
                    ((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat()
                )

                if (distFromCenter < regionSize) {
                    if (isEdge) centerEdge++
                    centerCount++
                } else {
                    if (isEdge) peripheralEdge++
                    peripheralCount++
                }
            }
        }

        if (centerCount == 0 || peripheralCount == 0) return 0f
        val centerDensity = centerEdge.toFloat() / centerCount
        val peripheralDensity = peripheralEdge.toFloat() / peripheralCount

        // 中央のエッジ密度が周辺の2倍以上ならマクロの可能性
        val ratio = if (peripheralDensity > 0) centerDensity / peripheralDensity else 0f
        return when {
            ratio > 3f && centerDensity > 0.2f -> 0.8f
            ratio > 2f && centerDensity > 0.15f -> 0.5f
            ratio > 1.5f -> 0.2f
            else -> 0f
        }
    }

    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (77 * r + 150 * g + 29 * b) shr 8
    }
}
