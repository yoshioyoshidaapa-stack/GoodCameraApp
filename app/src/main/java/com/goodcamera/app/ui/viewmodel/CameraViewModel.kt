package com.goodcamera.app.ui.viewmodel

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.goodcamera.app.camera.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
        cameraController?.capturePhoto()
    }

    fun switchCamera(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val useFront = !_uiState.value.usingFrontCamera
        _uiState.update { it.copy(usingFrontCamera = useFront) }
        cameraController?.startCamera(lifecycleOwner, previewView, useFront)
    }

    fun setGridType(type: GridType) {
        _uiState.update { it.copy(gridType = type) }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
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
