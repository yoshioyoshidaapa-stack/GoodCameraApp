package com.goodcamera.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.media.MediaActionSound
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.goodcamera.app.camera.*
import com.goodcamera.app.processing.FrameStacker
import com.goodcamera.app.processing.ImageProcessor
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
import kotlin.math.abs

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
        private const val PREFS_NAME = "good_camera_prefs"
        private const val KEY_SHUTTER_SOUND = "shutter_sound_enabled"
        /** 顔フォーカスの最小間隔 (ms) — チラつき防止 */
        private const val FACE_FOCUS_THROTTLE_MS = 1500L
        /** 顔位置が大きく動いた時のみ再フォーカスする閾値 (正規化座標) */
        private const val FACE_MOVE_THRESHOLD = 0.08f
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        CameraUiState(
            shutterSoundEnabled = prefs.getBoolean(KEY_SHUTTER_SOUND, true),
        )
    )
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var cameraController: CameraController? = null
    private var previewViewRef: PreviewView? = null
    private var aiAnalysisJob: Job? = null
    private val shutterSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
    }

    // 顔フォーカスのスロットリング状態
    private var lastFaceFocusTime = 0L
    private var lastFaceFocusCenterX = -1f
    private var lastFaceFocusCenterY = -1f

    fun startCamera(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (cameraController == null) {
            cameraController = CameraController(context).apply {
                onPreviewStarted = {
                    _uiState.update { it.copy(isPreviewActive = true) }
                }
                onCaptureComplete = { path ->
                    _uiState.update { it.copy(
                        isCaptureInProgress = false,
                        lastCapturedPath = path,
                    ) }
                }
                onError = { msg ->
                    _uiState.update { it.copy(
                        errorMessage = msg,
                        isCaptureInProgress = false,
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
                onFacesDetected = { faces ->
                    handleFacesDetected(faces)
                }
            }
        }

        previewViewRef = previewView
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

    fun capturePhoto() {
        if (_uiState.value.isCaptureInProgress) return
        _uiState.update { it.copy(isCaptureInProgress = true) }

        if (_uiState.value.shutterSoundEnabled) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        }

        if (_uiState.value.captureMode == CaptureMode.NIGHT) {
            captureNightMode()
        } else {
            cameraController?.capturePhoto()
        }
    }

    private fun captureNightMode() {
        val frameCount = _uiState.value.nightFrameCount
        _uiState.update { it.copy(nightCapturedFrames = 0, nightProcessing = false) }

        cameraController?.captureNightFrames(
            frameCount = frameCount,
            onFrameCaptured = { count ->
                _uiState.update { it.copy(nightCapturedFrames = count) }
            },
            onAllFrames = { frames ->
                if (frames.isEmpty()) {
                    _uiState.update { it.copy(
                        isCaptureInProgress = false,
                        errorMessage = "ナイトモード撮影に失敗しました",
                    ) }
                    return@captureNightFrames
                }
                _uiState.update { it.copy(nightProcessing = true) }

                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        // フレームスタッキング
                        val stacked = FrameStacker.stack(frames)
                        frames.forEach { it.recycle() }

                        // ナイトモード後処理
                        val processed = ImageProcessor.processNight(stacked)
                        if (processed !== stacked) stacked.recycle()

                        // 保存
                        val path = cameraController?.saveBitmapToGallery(processed)
                        processed.recycle()

                        _uiState.update { it.copy(
                            isCaptureInProgress = false,
                            nightCapturedFrames = 0,
                            nightProcessing = false,
                            lastCapturedPath = path,
                        ) }
                    } catch (e: Exception) {
                        frames.forEach { it.recycle() }
                        _uiState.update { it.copy(
                            isCaptureInProgress = false,
                            nightCapturedFrames = 0,
                            nightProcessing = false,
                            errorMessage = "ナイトモード処理に失敗: ${e.message}",
                        ) }
                    }
                }
            },
        )
    }

    fun switchCamera(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val useFront = !_uiState.value.usingFrontCamera
        _uiState.update { it.copy(usingFrontCamera = useFront) }
        cameraController?.startCamera(lifecycleOwner, previewView, useFront)
    }

    fun setCaptureMode(mode: CaptureMode) {
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

    fun setGridType(type: GridType) {
        _uiState.update { it.copy(gridType = type) }
    }

    fun setShutterSoundEnabled(enabled: Boolean) {
        _uiState.update { it.copy(shutterSoundEnabled = enabled) }
        prefs.edit().putBoolean(KEY_SHUTTER_SOUND, enabled).apply()
    }

    fun navigateTo(screen: AppScreen) {
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

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        aiAnalysisJob?.cancel()
        cameraController?.release()
        shutterSound.release()
    }
}
