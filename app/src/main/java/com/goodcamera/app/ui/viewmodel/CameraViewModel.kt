package com.goodcamera.app.ui.viewmodel

import android.graphics.Bitmap
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goodcamera.app.camera.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    var cameraController: CameraController? = null
        private set

    private var timerJob: Job? = null

    fun initController(controller: CameraController) {
        cameraController = controller

        controller.onCapabilities = { caps ->
            val maxZoom = controller.getMaxZoom()
            _uiState.update { it.copy(capabilities = caps, maxZoom = maxZoom) }
        }

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
    }

    fun openCamera(useFront: Boolean, surface: Surface) {
        _uiState.update { it.copy(usingFrontCamera = useFront, isPreviewActive = true) }
        cameraController?.openCamera(useFront, surface)
    }

    fun switchCamera(surface: Surface) {
        val useFront = !_uiState.value.usingFrontCamera
        _uiState.update { it.copy(usingFrontCamera = useFront, zoomLevel = 1f) }
        cameraController?.switchCamera(useFront, surface)
    }

    fun setCaptureMode(mode: CaptureMode) {
        _uiState.update { state ->
            val newSettings = when (mode) {
                CaptureMode.AUTO -> state.settings.copy(autoExposure = true, autoFocus = true)
                CaptureMode.PRO -> state.settings
                CaptureMode.HDR -> state.settings.copy(autoExposure = true, autoFocus = true)
                CaptureMode.NIGHT -> state.settings.copy(autoFocus = true)
            }
            state.copy(captureMode = mode, settings = newSettings)
        }
        applySettings()
    }

    fun setOutputFormat(format: OutputFormat) {
        _uiState.update { it.copy(outputFormat = format) }
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
        cameraController?.capturePhoto(
            mode = state.captureMode,
            outputFormat = state.outputFormat,
            hdrFrames = state.hdrFrameCount,
            nightFrames = state.nightFrameCount,
        )
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
        cameraController?.release()
    }
}
