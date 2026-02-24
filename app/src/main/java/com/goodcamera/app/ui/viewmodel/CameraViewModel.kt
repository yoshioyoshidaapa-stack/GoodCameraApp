package com.goodcamera.app.ui.viewmodel

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodcamera.app.camera.*
import com.goodcamera.app.processing.FrameStacker
import com.goodcamera.app.processing.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var cameraController: CameraController? = null

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
            }
        }

        cameraController!!.startCamera(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            useFront = _uiState.value.usingFrontCamera,
        )
    }

    fun capturePhoto() {
        if (_uiState.value.isCaptureInProgress) return
        _uiState.update { it.copy(isCaptureInProgress = true) }

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

    fun setGridType(type: GridType) {
        _uiState.update { it.copy(gridType = type) }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
        // マニュアルフォーカスからオートフォーカスに復帰
        _uiState.update { it.copy(
            settings = it.settings.copy(autoFocus = true),
        ) }
        cameraController?.tapToFocus(previewView, x, y)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        cameraController?.release()
    }
}
