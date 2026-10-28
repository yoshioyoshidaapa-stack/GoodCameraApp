package com.goodcamera.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.goodcamera.app.camera.*
import com.goodcamera.app.processing.FrameStacker
import com.goodcamera.app.processing.HdrToneMapper
import com.goodcamera.app.processing.ImageProcessor
import com.goodcamera.app.processing.SceneDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
        private const val PREFS_NAME = "good_camera_prefs"
        private const val KEY_SHUTTER_SOUND = "shutter_sound_enabled"
        private const val KEY_AUTO_PROCESS = "auto_process_enabled"
        /** HDR ブラケットの露出差 (EV) */
        private const val HDR_BRACKET_EV = 1f
        /** 露出補正を変えてから AE が落ち着くまで待つ最大時間 (ms) */
        private const val EXPOSURE_SETTLE_TIMEOUT_MS = 1500L
        /** 露出が落ち着いた後、そのフレームが撮影に使われるまで待つ時間 (ms) */
        private const val HDR_FRAME_SETTLE_MS = 300L
        /** 顔フォーカスの最小間隔 (ms) — チラつき防止 */
        private const val FACE_FOCUS_THROTTLE_MS = 1500L
        /** 顔位置が大きく動いた時のみ再フォーカスする閾値 (正規化座標) */
        private const val FACE_MOVE_THRESHOLD = 0.08f
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        CameraUiState(
            shutterSoundEnabled = prefs.getBoolean(KEY_SHUTTER_SOUND, true),
            autoProcessEnabled = prefs.getBoolean(KEY_AUTO_PROCESS, true),
        )
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var cameraController: CameraController? = null
    // PreviewView / LifecycleOwner は画面に属するので、画面が破棄されたら detachPreview() で手放す
    private var previewViewRef: PreviewView? = null
    private var lifecycleOwnerRef: LifecycleOwner? = null
    private var aiAnalysisJob: Job? = null
    /** AIモードで最後に判定されたシーンに対するおすすめ補正設定 */
    private var aiProcessingConfig: ImageProcessor.ProcessingConfig? = null
    private var burstJob: Job? = null
    private var timerJob: Job? = null
    private val shutterSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
    }

    // 顔フォーカスのスロットリング状態
    private var lastFaceFocusTime = 0L
    private var lastFaceFocusCenterX = -1f
    private var lastFaceFocusCenterY = -1f

    fun startCamera(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (cameraController == null) {
            // CameraController は applicationContext だけを保持する (Activity をリークしない)
            cameraController = CameraController(context.applicationContext).apply {
                onPreviewStarted = {
                    // カメラを付け直すと CameraController 側の顔検出は OFF に戻るので、
                    // こちらの状態も合わせて戻す (次のシーン判定で再び ON になる)
                    lastFaceFocusCenterX = -1f
                    lastFaceFocusCenterY = -1f
                    _uiState.update { it.copy(
                        isPreviewActive = true,
                        faceDetectionActive = false,
                        detectedFaces = emptyList(),
                        faceFocusLocked = false,
                    ) }
                }
                onCaptureComplete = { path ->
                    _uiState.update { it.copy(
                        isCaptureInProgress = false,
                        lastCapturedPath = path,
                        rawCapturing = false,
                    ) }
                }
                onRawCaptureComplete = { result ->
                    _uiState.update { it.copy(
                        isCaptureInProgress = false,
                        lastCapturedPath = result.jpegUri ?: result.dngUri,
                        rawCapturing = false,
                    ) }
                }
                onError = { msg ->
                    _uiState.update { it.copy(
                        errorMessage = msg,
                        isCaptureInProgress = false,
                        rawCapturing = false,
                    ) }
                }
                onFocusComplete = { success ->
                    _uiState.update { it.copy(focusLocked = success) }
                }
                onFocusDistanceChanged = { distance ->
                    _uiState.update { state ->
                        if (state.isMacroActive) {
                            state.copy(macroFocusDistance = distance)
                        } else {
                            state.copy(
                                settings = state.settings.copy(focusDistance = distance),
                            )
                        }
                    }
                }
                onCapabilitiesReady = { caps ->
                    _uiState.update { it.copy(capabilities = caps) }
                }
                // メインスレッドで呼ばれる (CameraController 側で載せ替え済み)
                onFacesDetected = { faces ->
                    handleFacesDetected(faces)
                }
            }
        }

        previewViewRef = previewView
        lifecycleOwnerRef = lifecycleOwner
        cameraController!!.startCamera(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            useFront = _uiState.value.usingFrontCamera,
        )

        // AIモードが既に選択されている場合、解析ループを開始
        if (_uiState.value.captureMode == CaptureMode.AI_AUTO && aiAnalysisJob?.isActive != true) {
            startAiAnalysisLoop()
        }
    }

    /**
     * 画面 (CameraScreen) が破棄されたときに呼ぶ。
     * 古い PreviewView / LifecycleOwner (= Activity) を持ち続けないようにする。
     */
    fun detachPreview(previewView: PreviewView) {
        cancelTimer()
        if (previewViewRef === previewView) {
            previewViewRef = null
            lifecycleOwnerRef = null
        }
    }

    /**
     * シャッターボタンが押されたとき。
     * セルフタイマーが設定されていればカウントダウン後に撮影する。
     * カウントダウン中にもう一度押すと取り消す。
     */
    fun onShutterPressed() {
        if (timerJob?.isActive == true) {
            cancelTimer()
            return
        }
        val seconds = _uiState.value.timerSeconds
        if (seconds <= 0) {
            capturePhoto()
            return
        }
        if (_uiState.value.isCaptureInProgress) return
        timerJob = viewModelScope.launch {
            try {
                for (remaining in seconds downTo 1) {
                    _uiState.update { it.copy(timerCountdown = remaining) }
                    delay(1000)
                }
            } finally {
                _uiState.update { it.copy(timerCountdown = 0) }
            }
            capturePhoto()
        }
    }

    private fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _uiState.update { it.copy(timerCountdown = 0) }
    }

    /** セルフタイマーの秒数を 0(OFF) → 3 → 5 → 10 → 0 の順に切り替える */
    fun cycleTimer() {
        cancelTimer()
        _uiState.update { state ->
            val next = when (state.timerSeconds) {
                0 -> 3
                3 -> 5
                5 -> 10
                else -> 0
            }
            state.copy(timerSeconds = next)
        }
    }

    fun capturePhoto() {
        if (_uiState.value.isCaptureInProgress) return
        _uiState.update { it.copy(isCaptureInProgress = true) }

        if (_uiState.value.shutterSoundEnabled) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        }

        val state = _uiState.value
        when {
            state.captureMode == CaptureMode.NIGHT -> captureNightMode()
            state.captureMode == CaptureMode.HDR -> captureHdrMode()
            state.outputFormat == OutputFormat.RAW_DNG -> captureRawPhoto()
            state.autoProcessEnabled -> captureWithAutoProcess()
            else -> cameraController?.capturePhoto()
                ?: _uiState.update { it.copy(isCaptureInProgress = false) }
        }
    }

    private fun captureRawPhoto() {
        val controller = cameraController ?: run {
            _uiState.update { it.copy(isCaptureInProgress = false) }
            return
        }
        _uiState.update { it.copy(rawCapturing = true) }

        val manualSettings = if (_uiState.value.captureMode == CaptureMode.PRO &&
            !_uiState.value.settings.autoExposure
        ) _uiState.value.settings else null

        controller.captureRawPhoto(manualSettings, onFinished = { restartCameraIfAttached() })
    }

    /**
     * 今画面に出ている PreviewView でカメラを付け直す。
     * 画面が無い (ギャラリー表示中など) ときは何もしない。次に画面が出たときに付け直される。
     */
    private fun restartCameraIfAttached() {
        val previewView = previewViewRef ?: return
        val lifecycleOwner = lifecycleOwnerRef ?: return
        cameraController?.startCamera(lifecycleOwner, previewView, _uiState.value.usingFrontCamera)
    }

    /**
     * 通常撮影 + 自動補正。
     * 撮影した画像に補正をかけてから保存する。AIモードでは判定したシーンに合わせた補正を使う。
     */
    private fun captureWithAutoProcess() {
        val controller = cameraController ?: run {
            _uiState.update { it.copy(isCaptureInProgress = false) }
            return
        }
        val config = if (_uiState.value.captureMode == CaptureMode.AI_AUTO) {
            aiProcessingConfig ?: ImageProcessor.ProcessingConfig()
        } else {
            ImageProcessor.ProcessingConfig()
        }

        viewModelScope.launch {
            try {
                val frame = controller.captureFrame()
                _uiState.update { it.copy(postProcessing = true) }
                val saved = withContext(Dispatchers.Default) {
                    processAndSave(controller, frame, "GoodCam") { ImageProcessor.process(it, config) }
                }
                onProcessedSaved(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Capture with auto process failed", t)
                _uiState.update { it.copy(errorMessage = "撮影に失敗しました: ${describe(t)}") }
            } finally {
                _uiState.update { it.copy(isCaptureInProgress = false, postProcessing = false) }
            }
        }
    }

    private fun captureNightMode() {
        val controller = cameraController ?: run {
            _uiState.update { it.copy(isCaptureInProgress = false) }
            return
        }
        val frameCount = _uiState.value.nightFrameCount
        _uiState.update { it.copy(nightCapturedFrames = 0, nightProcessing = false) }

        viewModelScope.launch {
            try {
                // フレームスタッキング (1枚撮るごとに足し込み、すぐ解放する)
                val stacked = captureAndStackNightFrames(controller, frameCount)
                if (stacked == null) {
                    _uiState.update { it.copy(errorMessage = "ナイトモード撮影に失敗しました") }
                    return@launch
                }
                _uiState.update { it.copy(nightProcessing = true) }

                // ナイトモード後処理 → 保存
                val saved = withContext(Dispatchers.Default) {
                    processAndSave(controller, stacked, "GoodCam_Night") { ImageProcessor.processNight(it) }
                }
                onProcessedSaved(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // OutOfMemoryError も含めて受け止め、アプリを落とさない
                Log.e(TAG, "Night mode failed", t)
                _uiState.update { it.copy(errorMessage = "ナイトモード処理に失敗: ${describe(t)}") }
            } finally {
                _uiState.update { it.copy(
                    isCaptureInProgress = false,
                    nightCapturedFrames = 0,
                    nightProcessing = false,
                ) }
            }
        }
    }

    /**
     * ナイトモードのフレームを撮影しながら平均化する。
     * 撮影中に端末を回してサイズが変わったフレームは捨てる。
     * @return 平均化した画像。1枚も撮れなかった場合は null
     */
    private suspend fun captureAndStackNightFrames(controller: CameraController, frameCount: Int): Bitmap? {
        val accumulator = FrameStacker.Accumulator()
        repeat(frameCount) {
            val frame = try {
                controller.captureFrame()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // エラーでも続行し、取得済みフレームで合成
                Log.e(TAG, "Night frame capture failed", e)
                null
            }
            if (frame != null) {
                val added = withContext(Dispatchers.Default) {
                    try {
                        accumulator.add(frame)
                    } finally {
                        frame.recycle()
                    }
                }
                if (!added) Log.w(TAG, "Night frame skipped (size changed during capture)")
                _uiState.update { it.copy(nightCapturedFrames = accumulator.frameCount) }
            }
        }
        return withContext(Dispatchers.Default) { accumulator.result() }
    }

    /**
     * HDRモード: 露出を変えて (暗い・普通・明るい) 撮影し、1枚に合成する。
     * 露出補正に対応していない端末では、通常撮影 + 自動補正にする。
     */
    private fun captureHdrMode() {
        val controller = cameraController ?: run {
            _uiState.update { it.copy(isCaptureInProgress = false) }
            return
        }
        if (controller.exposureCompensationStepEv <= 0f ||
            controller.exposureCompensationRange.first == controller.exposureCompensationRange.last
        ) {
            Log.w(TAG, "Exposure compensation not supported; HDR falls back to single capture")
            captureWithAutoProcess()
            return
        }
        _uiState.update { it.copy(hdrCapturedFrames = 0, hdrCapturing = true) }

        viewModelScope.launch {
            try {
                val merged = captureAndMergeHdrFrames(controller)
                if (merged == null) {
                    _uiState.update { it.copy(errorMessage = "HDR撮影に失敗しました") }
                    return@launch
                }
                _uiState.update { it.copy(postProcessing = true, hdrCapturing = false) }
                val saved = withContext(Dispatchers.Default) {
                    processAndSave(controller, merged, "GoodCam_HDR") { ImageProcessor.postProcessHdr(it) }
                }
                onProcessedSaved(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "HDR capture failed", t)
                _uiState.update { it.copy(errorMessage = "HDR処理に失敗: ${describe(t)}") }
            } finally {
                _uiState.update { it.copy(
                    isCaptureInProgress = false,
                    hdrCapturedFrames = 0,
                    hdrCapturing = false,
                    postProcessing = false,
                ) }
            }
        }
    }

    /**
     * 露出ブラケット撮影を行い、撮りながらHDR合成する。
     * 撮影後は露出補正を元の値に戻す。
     */
    private suspend fun captureAndMergeHdrFrames(controller: CameraController): Bitmap? {
        val stepEv = controller.exposureCompensationStepEv
        val range = controller.exposureCompensationRange
        val originalIndex = controller.exposureCompensationIndex
        val bracketSteps = (HDR_BRACKET_EV / stepEv).roundToInt().coerceAtLeast(1)
        // 暗い → 普通 → 明るい の順。範囲外は端に寄せ、重複は除く
        val indices = listOf(-bracketSteps, 0, bracketSteps)
            .map { (originalIndex + it).coerceIn(range) }
            .distinct()

        var merger: HdrToneMapper.Merger? = null
        try {
            for ((n, index) in indices.withIndex()) {
                withTimeoutOrNull(EXPOSURE_SETTLE_TIMEOUT_MS) {
                    controller.setExposureCompensationAndAwait(index)
                }
                // ZSL は直近のフレームを使うため、新しい露出のフレームが
                // バッファに入るまで数フレーム分待つ
                delay(HDR_FRAME_SETTLE_MS)
                val frame = controller.captureFrame()
                val ev = (index - originalIndex) * stepEv
                withContext(Dispatchers.Default) {
                    try {
                        val m = merger ?: HdrToneMapper.Merger(frame.width, frame.height)
                            .also { merger = it }
                        if (!m.add(frame, ev)) {
                            Log.w(TAG, "HDR frame skipped (size changed during capture)")
                        }
                    } finally {
                        frame.recycle()
                    }
                }
                _uiState.update { it.copy(hdrCapturedFrames = n + 1) }
            }
        } finally {
            controller.setExposureCompensation(originalIndex)
        }

        val m = merger ?: return null
        return withContext(Dispatchers.Default) { m.toneMap(ImageProcessor.HDR_SATURATION) }
    }

    /** 補正後に保存した結果 */
    private data class SavedResult(val path: String?, val processed: Boolean)

    /**
     * 画像に補正をかけて保存する (Dispatchers.Default で呼ぶこと)。
     * メモリ不足で補正できなかった場合は、補正前の画像を保存する。
     * 渡した source は必ず解放される。
     */
    private fun processAndSave(
        controller: CameraController,
        source: Bitmap,
        prefix: String,
        process: (Bitmap) -> Bitmap,
    ): SavedResult {
        // 補正に失敗しても撮った写真は失わないよう、補正前の画像を保存する
        val processed = try {
            process(source)
        } catch (t: Throwable) {
            if (t is CancellationException) {
                source.recycle()
                throw t
            }
            Log.e(TAG, "Processing failed; saving unprocessed image", t)
            null
        }
        return try {
            val toSave = processed ?: source
            SavedResult(controller.saveBitmapToGallery(toSave, prefix), processed != null)
        } finally {
            if (processed != null && processed !== source) processed.recycle()
            source.recycle()
        }
    }

    private fun onProcessedSaved(saved: SavedResult) {
        _uiState.update { state ->
            when {
                saved.path == null -> state.copy(errorMessage = "写真の保存に失敗しました")
                !saved.processed -> state.copy(
                    lastCapturedPath = saved.path,
                    errorMessage = "補正できなかったため、補正なしで保存しました",
                )
                else -> state.copy(lastCapturedPath = saved.path)
            }
        }
    }

    private fun describe(t: Throwable): String =
        if (t is OutOfMemoryError) "メモリ不足" else (t.message ?: t.javaClass.simpleName)

    /**
     * フロント/リアを切り替える。
     * カメラの付け直しは CameraScreen の LaunchedEffect(usingFrontCamera) が行う
     * (ここでも呼ぶと2回付け直しになり、ちらつく)。
     */
    fun switchCamera() {
        cancelTimer()
        _uiState.update { it.copy(usingFrontCamera = !it.usingFrontCamera) }
    }

    fun setCaptureMode(mode: CaptureMode) {
        cancelTimer()
        val prev = _uiState.value.captureMode
        _uiState.update { it.copy(captureMode = mode) }

        // 接写モードの切り替え
        if (mode == CaptureMode.MACRO && prev != CaptureMode.MACRO) {
            val minFocus = _uiState.value.capabilities.minFocusDistance
            cameraController?.enableMacroMode(minFocus)
            _uiState.update { it.copy(isMacroActive = true, macroFocusDistance = minFocus) }
        } else if (mode != CaptureMode.MACRO && prev == CaptureMode.MACRO) {
            cameraController?.disableMacroMode()
            _uiState.update { it.copy(isMacroActive = false) }
        }

        // AIモードの切り替え → シーン解析ループ開始/停止
        if (mode == CaptureMode.AI_AUTO && prev != CaptureMode.AI_AUTO) {
            startAiAnalysisLoop()
        } else if (mode != CaptureMode.AI_AUTO && prev == CaptureMode.AI_AUTO) {
            stopAiAnalysisLoop()
        }

        // Proモードの切り替え → 手動設定の適用/解除
        if (mode == CaptureMode.PRO && prev != CaptureMode.PRO) {
            // Proモード開始: 現在の設定を適用
            val settings = _uiState.value.settings
            if (!settings.autoExposure) {
                cameraController?.setIso(settings.iso)
                cameraController?.setShutterSpeed(settings.shutterSpeedNs)
            }
            if (settings.whiteBalance != WhiteBalanceMode.AUTO) {
                cameraController?.setWhiteBalance(settings.whiteBalance)
            }
        } else if (mode != CaptureMode.PRO && prev == CaptureMode.PRO) {
            // Proモード終了: 全てAutoに戻す
            cameraController?.resetToAuto()
            _uiState.update { it.copy(
                settings = it.settings.copy(
                    autoExposure = true,
                    autoFocus = true,
                    whiteBalance = WhiteBalanceMode.AUTO,
                ),
            ) }
        }
    }

    /**
     * AIモード時のシーン解析ループ。
     * PreviewViewからビットマップをキャプチャし、SceneDetectorで解析する。
     * ポートレート検出時に顔検出を有効化する。
     */
    private fun startAiAnalysisLoop() {
        aiAnalysisJob?.cancel()
        aiAnalysisJob = viewModelScope.launch {
            // プレビューが開始されるまで待つ
            while (isActive && previewViewRef == null) {
                delay(500)
            }
            while (isActive) {
                try {
                    _uiState.update { it.copy(aiAnalyzing = true) }
                    // PreviewView.getBitmap() はメインスレッドで呼ぶ必要がある
                    val bitmap = previewViewRef?.bitmap
                    if (bitmap != null) {
                        // 重い解析処理はバックグラウンドで実行
                        val analysis = withContext(Dispatchers.Default) {
                            SceneDetector.analyze(bitmap)
                        }
                        bitmap.recycle()
                        aiProcessingConfig = SceneDetector.recommendSettings(analysis).processingConfig
                        _uiState.update { it.copy(
                            aiDetectedScene = analysis.sceneType.label,
                            aiConfidence = analysis.confidence,
                            aiAnalyzing = false,
                        ) }
                        // ポートレートなら顔検出を自動有効化
                        onSceneDetected(analysis.sceneType)
                    } else {
                        _uiState.update { it.copy(aiAnalyzing = false) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AI analysis failed", e)
                    _uiState.update { it.copy(aiAnalyzing = false) }
                }
                delay(1500) // 1.5秒ごとに再解析
            }
        }
        Log.d(TAG, "AI analysis loop started")
    }

    private fun stopAiAnalysisLoop() {
        aiAnalysisJob?.cancel()
        aiAnalysisJob = null
        aiProcessingConfig = null
        // 顔検出も停止
        cameraController?.disableFaceDetection()
        _uiState.update { it.copy(
            aiAnalyzing = false,
            aiDetectedScene = "",
            aiConfidence = 0f,
            faceDetectionActive = false,
            detectedFaces = emptyList(),
            faceFocusLocked = false,
        ) }
        lastFaceFocusCenterX = -1f
        lastFaceFocusCenterY = -1f
        Log.d(TAG, "AI analysis loop stopped")
    }

    fun setMacroFocusDistance(distance: Float) {
        _uiState.update { it.copy(macroFocusDistance = distance) }
        cameraController?.setManualFocusDistance(distance)
    }

    fun setExposureCompensation(index: Int) {
        _uiState.update { it.copy(
            settings = it.settings.copy(exposureCompensation = index),
        ) }
        cameraController?.setExposureCompensation(index)
    }

    fun setFocusDistance(distance: Float) {
        _uiState.update { it.copy(
            settings = it.settings.copy(focusDistance = distance, autoFocus = false),
        ) }
        cameraController?.setManualFocusDistance(distance)
    }

    // --- Pro モード設定 ---

    fun setIso(iso: Int) {
        _uiState.update { it.copy(
            settings = it.settings.copy(iso = iso, autoExposure = false),
        ) }
        cameraController?.setIso(iso)
    }

    fun setShutterSpeed(ns: Long) {
        _uiState.update { it.copy(
            settings = it.settings.copy(shutterSpeedNs = ns, autoExposure = false),
        ) }
        cameraController?.setShutterSpeed(ns)
    }

    fun setWhiteBalance(mode: WhiteBalanceMode) {
        _uiState.update { it.copy(
            settings = it.settings.copy(whiteBalance = mode),
        ) }
        cameraController?.setWhiteBalance(mode)
    }

    fun setAutoExposure(enabled: Boolean) {
        _uiState.update { it.copy(
            settings = it.settings.copy(autoExposure = enabled),
        ) }
        if (enabled) {
            cameraController?.enableAutoExposure()
        } else {
            // 手動に切り替え: 現在のISO/SSを適用
            val settings = _uiState.value.settings
            cameraController?.setIso(settings.iso)
            cameraController?.setShutterSpeed(settings.shutterSpeedNs)
        }
    }

    fun setAutoFocus(enabled: Boolean) {
        _uiState.update { it.copy(
            settings = it.settings.copy(autoFocus = enabled),
        ) }
        if (enabled) {
            cameraController?.enableAutoFocus()
        } else {
            cameraController?.setManualFocusDistance(_uiState.value.settings.focusDistance)
        }
    }

    fun setOutputFormat(format: OutputFormat) {
        _uiState.update { it.copy(outputFormat = format) }
    }

    fun setGridType(type: GridType) {
        _uiState.update { it.copy(gridType = type) }
    }

    fun setShutterSoundEnabled(enabled: Boolean) {
        _uiState.update { it.copy(shutterSoundEnabled = enabled) }
        prefs.edit().putBoolean(KEY_SHUTTER_SOUND, enabled).apply()
    }

    fun setAutoProcessEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoProcessEnabled = enabled) }
        prefs.edit().putBoolean(KEY_AUTO_PROCESS, enabled).apply()
    }

    fun navigateTo(screen: AppScreen) {
        // カウントダウン中に別の画面へ移ったら撮影しない
        cancelTimer()
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
        // マニュアルフォーカスからオートフォーカスに復帰 & focusLockedリセット
        _uiState.update { it.copy(
            settings = it.settings.copy(autoFocus = true),
            focusLocked = false,
        ) }
        cameraController?.tapToFocus(previewView, x, y)
    }

    /**
     * AIシーン検出結果に基づいて顔検出の有効/無効を切り替える。
     * ポートレートと判定されたら顔検出を開始する。
     */
    fun onSceneDetected(sceneType: SceneDetector.SceneType) {
        val shouldDetectFaces = sceneType == SceneDetector.SceneType.PORTRAIT
        val currentlyActive = _uiState.value.faceDetectionActive
        if (shouldDetectFaces && !currentlyActive) {
            cameraController?.enableFaceDetection()
            _uiState.update { it.copy(faceDetectionActive = true) }
            Log.d(TAG, "Portrait detected → face detection ON")
        } else if (!shouldDetectFaces && currentlyActive) {
            cameraController?.disableFaceDetection()
            _uiState.update { it.copy(
                faceDetectionActive = false,
                detectedFaces = emptyList(),
                faceFocusLocked = false,
            ) }
            lastFaceFocusCenterX = -1f
            lastFaceFocusCenterY = -1f
            Log.d(TAG, "Non-portrait scene → face detection OFF")
        }
    }

    /**
     * Camera2の顔検出結果を処理し、最大の顔にフォーカスを合わせる。
     * スロットリングにより、顔位置が大きく動いた時だけ再フォーカスする。
     */
    private fun handleFacesDetected(faces: List<DetectedFace>) {
        if (faces.isEmpty()) {
            _uiState.update { it.copy(detectedFaces = emptyList(), faceFocusLocked = false) }
            return
        }

        // UIに顔位置を通知
        val normalizedFaces = faces.map { f ->
            NormalizedFace(f.centerX, f.centerY, f.width, f.height)
        }
        _uiState.update { it.copy(detectedFaces = normalizedFaces) }

        // 最大の顔を選択 (面積が一番大きい)
        val primaryFace = faces.maxByOrNull { it.width * it.height } ?: return
        val previewView = previewViewRef ?: return

        // スロットリング: 時間ベース + 移動距離ベース
        val now = System.currentTimeMillis()
        val dx = abs(primaryFace.centerX - lastFaceFocusCenterX)
        val dy = abs(primaryFace.centerY - lastFaceFocusCenterY)
        val hasMoved = dx > FACE_MOVE_THRESHOLD || dy > FACE_MOVE_THRESHOLD
        val timeElapsed = now - lastFaceFocusTime > FACE_FOCUS_THROTTLE_MS

        if (hasMoved || (timeElapsed && lastFaceFocusCenterX >= 0f)) {
            // 初回 or 顔が大きく動いた or 十分な時間が経過 → 再フォーカス
            if (timeElapsed || lastFaceFocusCenterX < 0f) {
                cameraController?.focusOnFace(previewView, primaryFace.centerX, primaryFace.centerY)
                lastFaceFocusTime = now
                lastFaceFocusCenterX = primaryFace.centerX
                lastFaceFocusCenterY = primaryFace.centerY
                _uiState.update { it.copy(faceFocusLocked = true) }
                Log.d(TAG, "Face focus → (${primaryFace.centerX}, ${primaryFace.centerY})")
            }
        }
    }

    // --- バーストモード ---

    /**
     * バースト撮影を開始する。
     * シャッターボタン長押しで呼ばれ、コルーチンループで連続撮影する。
     * ZSLモードにより最短間隔で撮影できる。
     */
    fun startBurst() {
        if (_uiState.value.isBurstActive) return

        @Suppress("SpellCheckingInspection")
        val burstId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        _uiState.update { it.copy(
            isBurstActive = true,
            burstCount = 0,
        ) }

        burstJob = viewModelScope.launch {
            var index = 1
            while (isActive && _uiState.value.isBurstActive) {
                val controller = cameraController ?: break
                if (_uiState.value.shutterSoundEnabled) {
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                }
                // 1枚の保存が終わってから次を撮る (保存が遅い端末で撮影待ちが溜まらないように)
                val path = controller.captureBurstPhoto(burstId, index)
                if (path != null) {
                    _uiState.update { state ->
                        state.copy(
                            burstCount = state.burstCount + 1,
                            lastCapturedPath = path,
                        )
                    }
                } else {
                    // 失敗が続いても空回りしないよう少し待つ
                    delay(200)
                }
                index++
            }
        }
        Log.d(TAG, "Burst started (burstId=$burstId)")
    }

    /**
     * バースト撮影を停止する。
     * シャッターボタンのリリースで呼ばれる。
     */
    fun stopBurst() {
        if (!_uiState.value.isBurstActive) return
        burstJob?.cancel()
        burstJob = null
        val count = _uiState.value.burstCount
        _uiState.update { it.copy(isBurstActive = false) }
        Log.d(TAG, "Burst stopped ($count photos)")
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        aiAnalysisJob?.cancel()
        burstJob?.cancel()
        cameraController?.release()
        shutterSound.release()
    }
}
