package com.goodcamera.app.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
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
    /** カメラ性能取得完了コールバック */
    var onCapabilitiesReady: ((CameraCapabilities) -> Unit)? = null

    private var orientationListener: OrientationEventListener? = null

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

                // カメラ性能を取得してUIに通知
                queryCameraCapabilities()

                // デバイスの回転に追従して撮影画像の向きを設定
                startOrientationListener()

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

    /**
     * 接写モードを有効にする。
     * - AF_MODE_MACRO: 近距離特化のAFアルゴリズムに切り替え
     * - LENS_FOCUS_DISTANCE: 最近接 (minFocusDistance) にフォーカスを寄せる
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun enableMacroMode(minFocusDistance: Float) {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_MACRO,
            )
            .setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                minFocusDistance,
            )
            .build()
        camera2Control.setCaptureRequestOptions(options)
        Log.d(TAG, "Macro mode ON – AF_MODE_MACRO, focusDist=$minFocusDistance")
    }

    /**
     * 接写モード中にフォーカス距離を変更する (スライダー連動)。
     * AF を OFF にし、指定距離にマニュアルフォーカスを設定する。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setManualFocusDistance(distance: Float) {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF,
            )
            .setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                distance,
            )
            .build()
        camera2Control.setCaptureRequestOptions(options)
    }

    /**
     * 接写モードを無効にし、通常の CONTINUOUS_PICTURE AF に戻す。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun disableMacroMode() {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            .clearCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE)
            .build()
        camera2Control.setCaptureRequestOptions(options)
        Log.d(TAG, "Macro mode OFF – restored CONTINUOUS_PICTURE AF")
    }

    /**
     * カメラハードウェアから性能情報を取得し、UIに通知する。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun queryCameraCapabilities() {
        val cam = camera ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val minFocus = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
            ) ?: 0f
            val capabilities = CameraCapabilities(
                minFocusDistance = minFocus,
            )
            Log.d(TAG, "Camera capabilities: minFocusDistance=$minFocus")
            onCapabilitiesReady?.invoke(capabilities)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query camera capabilities", e)
        }
    }

    /**
     * デバイスの物理的な向きを監視し、ImageCapture の targetRotation を更新する。
     * これにより撮影画像が端末の傾きに応じた正しい向きで保存される。
     */
    private fun startOrientationListener() {
        orientationListener?.disable()
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation in 45 until 135 -> Surface.ROTATION_270
                    orientation in 135 until 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                imageCapture?.targetRotation = rotation
            }
        }
        orientationListener?.enable()
    }

    fun release() {
        orientationListener?.disable()
        orientationListener = null
        cameraProvider?.unbindAll()
    }
}
