package com.goodcamera.app.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "CameraController"
        /** タップフォーカスのメータリング領域サイズ (小さいほど収束が速い) */
        private const val METERING_POINT_SIZE = 0.1f
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    var onCaptureComplete: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPreviewStarted: (() -> Unit)? = null
    /** フォーカスロック完了コールバック (true=成功, false=失敗) */
    var onFocusComplete: ((Boolean) -> Unit)? = null

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        useFront: Boolean,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                // Camera2 interop: プレビューにCONTINUOUS_PICTURE AFを強制設定
                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                val preview = previewBuilder.build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                // ZSL: AF完了を待たずに直前のフレームから撮影
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
                Camera2Interop.Extender(captureBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                imageCapture = captureBuilder.build()

                val selector = if (useFront) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)

                // 起動直後にセンターAFをトリガー (小領域で高速収束)
                triggerCenterFocus(previewView)

                onPreviewStarted?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                onError?.invoke("Camera start failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * タップ位置に最速フォーカスを実行する。
     * - 小さいメータリング領域 (10%) で位相差AFの収束を高速化
     * - AF専用フラグで AE の再計算を省略し遅延を排除
     * - autoCancelDuration=1.5s で素早く連続AFに復帰
     * - 完了コールバックでUIにロック状態を通知
     */
    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y, METERING_POINT_SIZE)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(1500, TimeUnit.MILLISECONDS)
            .build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                val result = future.get()
                onFocusComplete?.invoke(result.isFocusSuccessful)
            } catch (_: Exception) {
                onFocusComplete?.invoke(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun triggerCenterFocus(previewView: PreviewView) {
        val cam = camera ?: return
        val centerPoint = previewView.meteringPointFactory.createPoint(
            previewView.width / 2f, previewView.height / 2f, METERING_POINT_SIZE,
        )
        val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(1500, TimeUnit.MILLISECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun capturePhoto() {
        val capture = imageCapture ?: run {
            onError?.invoke("Camera not ready")
            return
        }

        @Suppress("SpellCheckingInspection")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "GoodCam_$timestamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/GoodCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri?.toString() ?: ""
                    onCaptureComplete?.invoke(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    this@CameraController.onError?.invoke("Capture failed: ${exception.message}")
                }
            },
        )
    }

    fun release() {
        cameraProvider?.unbindAll()
    }
}
