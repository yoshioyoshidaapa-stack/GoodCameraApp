package com.goodcamera.app.processing

import android.graphics.Bitmap

/**
 * 後処理パイプラインのオーケストレーター
 *
 * 撮影モードに応じて最適な後処理チェーンを適用する。
 * 処理順序が画質に直結するため、順序を慎重に設計している:
 *   1. 自動ホワイトバランス補正（色かぶりを最初に除去）
 *   2. かすみ除去（大気散乱の影響を復元 — NR前に実行しコントラスト回復優先）
 *   3. ノイズリダクション（ノイズを消してからシャープニング）
 *   4. ブレ/ピンぼけ自動補正（ブラー度を検出し適応的に復元）
 *   5. 自動レベル補正（トーンレンジを最適化）
 *   6. 知覚補正（人間の視覚特性に基づく局所コントラスト・トーン）
 *   7. シャープニング（最後にエッジを強調）
 */
object ImageProcessor {

    data class ProcessingConfig(
        val autoWbEnabled: Boolean = true,
        val denoiseEnabled: Boolean = true,
        val denoiseStrength: DenoiseStrength = DenoiseStrength.MEDIUM,
        val deblurEnabled: Boolean = true,
        val perceptualEnabled: Boolean = true,
        val sharpenEnabled: Boolean = true,
        val sharpenAmount: Float = 1.2f,
        val autoLevelsEnabled: Boolean = true,
        val autoLevelsClip: Float = 1.0f,
        val dehazeEnabled: Boolean = false,
        val dehazeStrength: Float = 0.7f,
    )

    enum class DenoiseStrength(val radius: Int, val sigmaSpace: Float, val sigmaColor: Float) {
        LIGHT(2, 8f, 20f),
        MEDIUM(3, 10f, 30f),
        STRONG(4, 14f, 40f),
    }

    /**
     * 標準後処理パイプラインを適用
     *
     * @param checkCancelled 各ステップの間に呼ばれる。中断したい場合は例外を投げる
     *        (例: コルーチンの ensureActive())。
     */
    fun process(
        bitmap: Bitmap,
        config: ProcessingConfig = ProcessingConfig(),
        checkCancelled: () -> Unit = {},
    ): Bitmap {
        var result = bitmap
        try {
            // Step 1: 自動ホワイトバランス補正（色かぶりを最初に除去）
            checkCancelled()
            if (config.autoWbEnabled) {
                val wbCorrected = WhiteBalanceCorrector.correct(result)
                if (result !== bitmap) result.recycle()
                result = wbCorrected
            }

            // Step 2: かすみ除去（Dark Channel Priorベース）
            checkCancelled()
            if (config.dehazeEnabled) {
                val dehazed = DehazeFilter.dehaze(result, strength = config.dehazeStrength)
                if (result !== bitmap) result.recycle()
                result = dehazed
            }

            // Step 3: ノイズリダクション
            checkCancelled()
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

            // Step 4: ブレ/ピンぼけ自動補正
            checkCancelled()
            if (config.deblurEnabled) {
                val analysis = DeblurFilter.detectBlur(result)
                if (analysis.blurLevel != DeblurFilter.BlurLevel.SHARP) {
                    val corrected = DeblurFilter.correctBlur(result, analysis)
                    if (result !== bitmap) result.recycle()
                    result = corrected
                }
            }

            // Step 5: 自動レベル補正
            checkCancelled()
            if (config.autoLevelsEnabled) {
                val adjusted = AutoLevels.autoContrast(result, config.autoLevelsClip)
                if (result !== bitmap) result.recycle()
                result = adjusted
            }

            // Step 6: 知覚補正（人間の目と脳に近い補正）
            checkCancelled()
            if (config.perceptualEnabled) {
                val enhanced = PerceptualEnhancer.enhance(result)
                if (result !== bitmap) result.recycle()
                result = enhanced
            }

            // Step 7: シャープニング
            checkCancelled()
            if (config.sharpenEnabled) {
                val sharpened = Sharpening.unsharpMask(
                    result,
                    amount = config.sharpenAmount,
                    threshold = 4,
                )
                if (result !== bitmap) result.recycle()
                result = sharpened
            }
        } catch (t: Throwable) {
            // 中断・失敗時は途中結果を解放してから投げ直す
            if (result !== bitmap) result.recycle()
            throw t
        }

        return result
    }

    /**
     * HDR専用パイプライン
     * HDR合成後に軽めの後処理を適用
     */
    fun processHdr(frames: List<Bitmap>): Bitmap {
        // Reinhard トーンマッピングでHDR合成
        val hdr = HdrToneMapper.mergeAndToneMap(frames, saturation = HDR_SATURATION)
        val result = postProcessHdr(hdr)
        if (result !== hdr) hdr.recycle()
        return result
    }

    /** HDR合成器で使う彩度 */
    const val HDR_SATURATION = 1.15f

    /**
     * HDR合成済み画像への後処理
     * HDR後は軽めの処理（合成時にノイズが平均化されているため）
     */
    fun postProcessHdr(hdr: Bitmap): Bitmap {
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
            denoiseStrength = DenoiseStrength.MEDIUM, // スタッキングでノイズ低減済みのためMEDIUMで十分
            deblurEnabled = false, // スタッキングでランダムノイズは平均化済み、デブラー不要
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
