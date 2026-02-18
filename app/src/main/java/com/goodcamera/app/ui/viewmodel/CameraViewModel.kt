package com.goodcamera.app.ui.viewmodel

import android.graphics.Bitmap
import android.view.Surface
import android.view.TextureView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodcamera.app.camera.*
import com.goodcamera.app.processing.SceneDetector
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

class CameraViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    var cameraController: CameraController? = null
        private set

    private var timerJob: Job? = null
    private var aiAnalysisJob: Job? = null

    // AI解析結果を保持（撮影時の後処理設定に使用）
    var lastSceneRecommendation: SceneDetector.SceneRecommendation? = null
        private set

    // プレビューフレーム取得用のTextureView参照
    var previewTextureView: TextureView? = null

    fun initController(controller: CameraController) {
        cameraController = controller

        controller.onCapabilities = { caps ->
            val maxZoom = controller.getMaxZoom()
            _uiState.update { it.copy(capabilities = caps, maxZoom = maxZoom) }
        }

        controller.onPreviewStarted = { onPreviewReady() }

        controller.onCaptureComplete = { path ->
            _uiState.update { it.copy(
                isCaptureInProgress = false,
                lastCapturedPath = path,
                showReviewScreen = true,
                reviewImagePath = path,
            ) }
        }

        controller.onError = { msg ->
            _uiState.update { it.copy(
                errorMessage = msg,
                isCaptureInProgress = false,
            ) }
        }

        // 連写コールバック
        controller.onBurstFrame = { _, count ->
            _uiState.update { it.copy(burstCount = count) }
        }

        controller.onBurstFinished = { paths ->
            _uiState.update { it.copy(
                isBurstActive = false,
                burstCount = 0,
                burstSavedPaths = paths,
                isCaptureInProgress = false,
            ) }
        }
    }

    private fun onPreviewReady() {
        _uiState.update { it.copy(isPreviewActive = true) }
        // AIモードならシーン解析を遅延起動（プレビュー安定後に開始）
        if (_uiState.value.captureMode == CaptureMode.AI_AUTO && aiAnalysisJob == null) {
            startAiAnalysisDeferred()
        }
    }

    fun openCamera(useFront: Boolean, surface: Surface) {
        _uiState.update { it.copy(usingFrontCamera = useFront) }
        cameraController?.openCamera(useFront, surface)
    }

    fun pauseCamera() {
        stopAiAnalysis()
        cameraController?.closeCamera()
        _uiState.update { it.copy(isPreviewActive = false) }
    }

    fun resumeCamera(surface: Surface) {
        val useFront = _uiState.value.usingFrontCamera
        cameraController?.openCamera(useFront, surface)
    }

    fun switchCamera(surface: Surface) {
        val useFront = !_uiState.value.usingFrontCamera
        _uiState.update { it.copy(usingFrontCamera = useFront, zoomLevel = 1f) }
        cameraController?.switchCamera(useFront, surface)
    }

    fun setCaptureMode(mode: CaptureMode) {
        // 連写中にモード変更された場合は停止
        if (_uiState.value.isBurstActive) {
            stopBurst()
        }

        _uiState.update { state ->
            val newSettings = when (mode) {
                CaptureMode.AI_AUTO -> state.settings.copy(autoExposure = true, autoFocus = true)
                CaptureMode.AUTO -> state.settings.copy(autoExposure = true, autoFocus = true)
                CaptureMode.PRO -> state.settings
                CaptureMode.HDR -> state.settings.copy(autoExposure = true, autoFocus = true)
                CaptureMode.NIGHT -> state.settings.copy(autoFocus = true)
                CaptureMode.BURST -> state.settings.copy(autoExposure = true, autoFocus = true)
            }
            state.copy(captureMode = mode, settings = newSettings)
        }
        applySettings()

        if (mode == CaptureMode.AI_AUTO) {
            startAiAnalysis()
        } else {
            stopAiAnalysis()
        }
    }

    fun setOutputFormat(format: OutputFormat) {
        _uiState.update { it.copy(outputFormat = format) }
        // RAW+JPEG選択時にRAW ImageReaderを遅延初期化
        if (format == OutputFormat.RAW_DNG) {
            cameraController?.ensureRawSessionIfNeeded()
        }
    }

    fun setIso(iso: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(iso = iso, autoExposure = false)) }
        applySettings()
    }

    fun setShutterSpeed(ns: Long) {
        _uiState.update { it.copy(settings = it.settings.copy(shutterSpeedNs = ns, autoExposure = false)) }
        applySettings()
    }

    fun setWhiteBalance(wb: WhiteBalanceMode) {
        _uiState.update { it.copy(settings = it.settings.copy(whiteBalance = wb)) }
        applySettings()
    }

    fun setFocusDistance(distance: Float) {
        _uiState.update { it.copy(settings = it.settings.copy(focusDistance = distance, autoFocus = false)) }
        applySettings()
    }

    fun setAutoExposure(auto: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(autoExposure = auto)) }
        applySettings()
    }

    fun setAutoFocus(auto: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(autoFocus = auto)) }
        applySettings()
    }

    fun setExposureCompensation(ev: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(exposureCompensation = ev)) }
        applySettings()
    }

    // ---- ズーム ----

    fun setZoomLevel(zoom: Float) {
        val state = _uiState.value
        val clamped = zoom.coerceIn(1f, state.maxZoom)
        _uiState.update { it.copy(zoomLevel = clamped) }
        cameraController?.setZoom(clamped)
    }

    // ---- セルフタイマー ----

    fun setTimerSeconds(seconds: Int) {
        _uiState.update { it.copy(timerSeconds = seconds) }
    }

    fun capturePhoto() {
        val state = _uiState.value
        if (state.isCaptureInProgress || state.timerCountdown > 0) return

        if (state.timerSeconds > 0) {
            startTimerCapture(state.timerSeconds)
        } else {
            doCapture()
        }
    }

    private fun startTimerCapture(seconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            for (i in seconds downTo 1) {
                _uiState.update { it.copy(timerCountdown = i) }
                delay(1000)
            }
            _uiState.update { it.copy(timerCountdown = 0) }
            doCapture()
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        _uiState.update { it.copy(timerCountdown = 0) }
    }

    private fun doCapture() {
        val state = _uiState.value
        _uiState.update { it.copy(isCaptureInProgress = true) }

        // AIモード: シーン解析結果に基づいて撮影モードを自動選択
        val recommendation = lastSceneRecommendation
        if (state.captureMode == CaptureMode.AI_AUTO && recommendation != null) {
            val effectiveMode = when {
                recommendation.useNightMode -> CaptureMode.NIGHT
                recommendation.useHdr -> CaptureMode.HDR
                else -> CaptureMode.AUTO
            }
            // AI推奨の露出補正を適用
            if (recommendation.exposureCompensation != 0) {
                cameraController?.updateSettings(
                    state.settings.copy(exposureCompensation = recommendation.exposureCompensation)
                )
            }
            cameraController?.capturePhoto(
                mode = effectiveMode,
                outputFormat = state.outputFormat,
                hdrFrames = state.hdrFrameCount,
                nightFrames = state.nightFrameCount,
            )
        } else {
            cameraController?.capturePhoto(
                mode = state.captureMode,
                outputFormat = state.outputFormat,
                hdrFrames = state.hdrFrameCount,
                nightFrames = state.nightFrameCount,
            )
        }
    }

    // ---- 連写 (Burst) ----

    fun startBurst() {
        if (_uiState.value.isBurstActive) return
        _uiState.update { it.copy(
            isBurstActive = true,
            isCaptureInProgress = true,
            burstCount = 0,
            burstSavedPaths = emptyList(),
        ) }
        cameraController?.startBurst()
    }

    fun stopBurst() {
        if (!_uiState.value.isBurstActive) return
        cameraController?.stopBurst()
        // state update は onBurstFinished コールバックで行う
    }

    // ---- AI シーン解析 ----

    /**
     * カメラ起動直後にAI解析を遅延起動する。
     * プレビューが安定するまで500ms待機してから解析ループを開始。
     * 起動時のCPU負荷を軽減し、カメラプレビューの表示を優先する。
     */
    private fun startAiAnalysisDeferred() {
        stopAiAnalysis()
        aiAnalysisJob = viewModelScope.launch {
            // プレビュー安定まで待機（起動時のカメラパイプライン初期化と競合回避）
            delay(500)
            _uiState.update { it.copy(aiAnalyzing = true) }
            while (isActive) {
                analyzeCurrentScene()
                delay(1500)
            }
        }
    }

    private fun startAiAnalysis() {
        stopAiAnalysis()
        aiAnalysisJob = viewModelScope.launch {
            _uiState.update { it.copy(aiAnalyzing = true) }
            while (isActive) {
                analyzeCurrentScene()
                delay(1500)
            }
        }
    }

    private fun stopAiAnalysis() {
        aiAnalysisJob?.cancel()
        aiAnalysisJob = null
        _uiState.update { it.copy(aiAnalyzing = false, aiDetectedScene = "", aiConfidence = 0f) }
        lastSceneRecommendation = null
    }

    private suspend fun analyzeCurrentScene() {
        val tv = previewTextureView ?: return
        val bitmap = withContext(Dispatchers.Main) {
            tv.bitmap
        } ?: return

        try {
            val analysis = withContext(Dispatchers.Default) {
                SceneDetector.analyze(bitmap)
            }

            val recommendation = SceneDetector.recommendSettings(analysis)
            lastSceneRecommendation = recommendation

            _uiState.update {
                it.copy(
                    aiDetectedScene = analysis.sceneType.label,
                    aiConfidence = analysis.confidence,
                    // AIが推奨する露出補正を自動適用
                    settings = it.settings.copy(
                        exposureCompensation = recommendation.exposureCompensation,
                    ),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ---- グリッド ----

    fun setGridType(type: GridType) {
        _uiState.update { it.copy(gridType = type) }
    }

    // ---- ナビゲーション ----

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    // ---- タップフォーカス ----

    fun tapToFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        cameraController?.tapToFocus(x, y, viewWidth, viewHeight)
    }

    fun setAutoContrast(enabled: Boolean) {
        _uiState.update { it.copy(autoContrastEnabled = enabled) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearLastCapture() {
        _uiState.update { it.copy(lastCapturedPath = null) }
    }

    fun openReviewScreen(path: String) {
        _uiState.update { it.copy(showReviewScreen = true, reviewImagePath = path) }
    }

    fun closeReviewScreen() {
        _uiState.update { it.copy(showReviewScreen = false, reviewImagePath = null) }
    }

    fun saveProcessedImage(bitmap: Bitmap) {
        cameraController?.saveBitmapToMediaStore(bitmap)
        _uiState.update { it.copy(showReviewScreen = false, reviewImagePath = null) }
    }

    private fun applySettings() {
        cameraController?.updateSettings(_uiState.value.settings)
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        aiAnalysisJob?.cancel()
        if (_uiState.value.isBurstActive) {
            cameraController?.stopBurst()
        }
        cameraController?.release()
    }
}
