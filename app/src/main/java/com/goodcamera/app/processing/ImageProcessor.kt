package com.goodcamera.app.processing

import android.graphics.Bitmap

/**
 * 後処理パイプラインのオーケストレーター
 *
 * 撮影モードに応じて最適な後処理チェーンを適用する。
 * 処理順序が画質に直結するため、順序を慎重に設計している:
 *   1. ノイズリダクション（先にノイズを消してからシャープニング）
 *   2. ブレ/ピンぼけ自動補正（ブラー度を検出し適応的に復元）
 *   3. 自動レベル補正（トーンレンジを最適化）
 *   4. 知覚補正（人間の視覚特性に基づく局所コントラスト・色恒常性・トーン）
 *   5. シャープニング（最後にエッジを強調）
 */
object ImageProcessor {

    data class ProcessingConfig(
        val denoiseEnabled: Boolean = true,
        val denoiseStrength: DenoiseStrength = DenoiseStrength.MEDIUM,
        val deblurEnabled: Boolean = true,
        val perceptualEnabled: Boolean = true,
        val sharpenEnabled: Boolean = true,
        val sharpenAmount: Float = 1.2f,
        val autoLevelsEnabled: Boolean = true,
        val autoLevelsClip: Float = 1.0f,
    )

    enum class DenoiseStrength(val radius: Int, val sigmaSpace: Float, val sigmaColor: Float) {
        LIGHT(2, 8f, 20f),
        MEDIUM(3, 10f, 30f),
        STRONG(4, 14f, 40f),
    }

    /**
     * 標準後処理パイプラインを適用
     */
    fun process(bitmap: Bitmap, config: ProcessingConfig = ProcessingConfig()): Bitmap {
        var result = bitmap

        // Step 1: ノイズリダクション
        if (config.denoiseEnabled) {
            val strength = config.denoiseStrength
            val denoised = NoiseReduction.bilateralFilter(
                result,
                radius = strength.radius,
                sigmaSpace = strength.sigmaSpace,
                sigmaColor = strength.sigmaColor,
            )
            if (result !== bitmap) result.recycle()
            result = denoised
        }

        // Step 2: ブレ/ピンぼけ自動補正
        if (config.deblurEnabled) {
            val analysis = DeblurFilter.detectBlur(result)
            if (analysis.blurLevel != DeblurFilter.BlurLevel.SHARP) {
                val corrected = DeblurFilter.correctBlur(result, analysis)
                if (result !== bitmap) result.recycle()
                result = corrected
            }
        }

        // Step 3: 自動レベル補正
        if (config.autoLevelsEnabled) {
            val adjusted = AutoLevels.autoContrast(result, config.autoLevelsClip)
            if (result !== bitmap) result.recycle()
            result = adjusted
        }

        // Step 4: 知覚補正（人間の目と脳に近い補正）
        if (config.perceptualEnabled) {
            val enhanced = PerceptualEnhancer.enhance(result)
            if (result !== bitmap) result.recycle()
            result = enhanced
        }

        // Step 5: シャープニング
        if (config.sharpenEnabled) {
            val sharpened = Sharpening.unsharpMask(
                result,
                amount = config.sharpenAmount,
                threshold = 4,
            )
            if (result !== bitmap) result.recycle()
            result = sharpened
        }

        return result
    }

    /**
     * HDR専用パイプライン
     * HDR合成後に軽めの後処理を適用
     */
    fun processHdr(frames: List<Bitmap>): Bitmap {
        // Reinhard トーンマッピングでHDR合成
        val hdr = HdrToneMapper.mergeAndToneMap(frames, saturation = 1.15f)

        // HDR後は軽めの処理（合成時にノイズが平均化されているため）
        return process(hdr, ProcessingConfig(
            denoiseEnabled = true,
            denoiseStrength = DenoiseStrength.LIGHT,
            sharpenEnabled = true,
            sharpenAmount = 0.8f,
            autoLevelsEnabled = false, // HDRトーンマップ済みなので不要
        ))
    }

    /**
     * ナイトモード専用パイプライン
     * フレームスタッキング後に強めのノイズ除去
     */
    fun processNight(stackedBitmap: Bitmap): Bitmap {
        return process(stackedBitmap, ProcessingConfig(
            denoiseEnabled = true,
            denoiseStrength = DenoiseStrength.STRONG,
            sharpenEnabled = true,
            sharpenAmount = 1.5f, // ノイズ除去で失われたエッジを回復
            autoLevelsEnabled = true,
            autoLevelsClip = 0.5f, // 暗部のディテールを残すため控えめ
        ))
    }

    /**
     * 高速処理パイプライン（プレビュー用）
     * 解像度を下げて高速に適用
     */
    fun processPreview(bitmap: Bitmap): Bitmap {
        // プレビューは高速ノイズ除去 + 軽いシャープニングのみ
        var result = NoiseReduction.fastDenoise(bitmap, strength = 0.4f)
        result = Sharpening.unsharpMask(result, amount = 0.8f, radius = 1, threshold = 6)
        return result
    }
}
