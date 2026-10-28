package com.goodcamera.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
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
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.goodcamera.app.util.MediaStoreSaver
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * @param context Activity を渡しても内部では applicationContext だけを保持する
 *        (画面の作り直し後も古い Activity をつかみ続けてリークしないように)
 */
class CameraController(context: Context) {

    private val context: Context = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(this.context)
    /** 撮影画像のデコードなど重い処理をメインスレッドから外すための専用スレッド */
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        private const val TAG = "CameraController"
        /** タップフォーカスのメータリング領域サイズ (小さいほど収束が速い) */
        private const val METERING_POINT_SIZE = 0.1f
        /** 顔フォーカスのメータリング領域サイズ (顔の大きさに応じて広めに) */
        private const val FACE_METERING_POINT_SIZE = 0.15f
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    var onCaptureComplete: ((String) -> Unit)? = null
    var onRawCaptureComplete: ((RawCaptureHelper.RawCaptureResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPreviewStarted: (() -> Unit)? = null
    /** フォーカスロック完了コールバック (true=成功, false=失敗) */
    var onFocusComplete: ((Boolean) -> Unit)? = null
    /** タップToフォーカス後のフォーカス距離通知コールバック (diopters) */
    var onFocusDistanceChanged: ((Float) -> Unit)? = null
    /** カメラ性能取得完了コールバック */
    var onCapabilitiesReady: ((CameraCapabilities) -> Unit)? = null
    /** 顔検出コールバック (正規化座標 0..1 のリスト) */
    var onFacesDetected: ((List<DetectedFace>) -> Unit)? = null

    private var orientationListener: OrientationEventListener? = null
    /** タップToフォーカス中にフォーカス距離の読み取りを待っているか */
    @Volatile
    private var awaitingFocusDistance = false
    /** AF完了コールバックを一度だけ発火するためのフラグ */
    @Volatile
    private var awaitingFocusResult = false
    /** AFスキャンが実際に開始されたか (古いFOCUSED_LOCKEDを誤検知しないためのガード) */
    @Volatile
    private var focusScanStarted = false
    /** 顔検出結果をコールバックに転送するか */
    @Volatile
    private var faceDetectionEnabled = false
    /** センサーのアクティブ領域 (顔座標→正規化座標の変換に使用) */
    private var sensorActiveRect: Rect? = null
    /** センサー回転角度 (0, 90, 180, 270) */
    private var sensorOrientation: Int = 0
    /** フロントカメラかどうか (プレビューのミラーリングに影響) */
    private var isFrontCamera: Boolean = false
    /** 現在のデバイスディスプレイ回転 (度数: 0, 90, 180, 270) */
    @Volatile
    private var displayRotationDegrees: Int = 0

    private var rawCaptureHelper: RawCaptureHelper? = null
    private var currentCameraId: String? = null
    private var currentCharacteristics: CameraCharacteristics? = null
    @Volatile
    private var lastIso: Int = 200
    @Volatile
    private var lastExposureTimeNs: Long = 33_333_333L
    @Volatile
    private var lastFocusDist: Float = 0f
    @Volatile
    private var lastAwbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    @androidx.camera.core.ExperimentalZeroShutterLag
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        useFront: Boolean,
    ) {
        // カメラ切替時に顔検出状態をリセット
        faceDetectionEnabled = false
        isFrontCamera = useFront

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                // Camera2 interop: AF + 顔検出をセッション開始時に設定
                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                        CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE,
                    )
                    .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureTimeNs = it }
                            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocusDist = it }
                            result.get(CaptureResult.CONTROL_AWB_MODE)?.let { lastAwbMode = it }

                            // フォーカス距離の読み取り & AF完了通知
                            if (awaitingFocusDistance || awaitingFocusResult) {
                                val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                                // AFスキャンが実際に開始されたことを検出してから結果を受け付ける。
                                // これにより、前回のフレームに残った古いFOCUSED_LOCKEDを
                                // 誤って新しいフォーカス結果として拾うレースコンディションを防ぐ。
                                if (afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
                                ) {
                                    focusScanStarted = true
                                }
                                if (focusScanStarted &&
                                    (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)
                                ) {
                                    val focused = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                    if (awaitingFocusResult) {
                                        awaitingFocusResult = false
                                        onFocusComplete?.invoke(focused)
                                    }
                                    if (awaitingFocusDistance) {
                                        awaitingFocusDistance = false
                                        val distance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                                        if (distance != null) {
                                            onFocusDistanceChanged?.invoke(distance)
                                        }
                                    }
                                    focusScanStarted = false
                                }
                            }

                            // 顔検出結果の読み取り (モードはセッション開始時に設定済み)
                            if (faceDetectionEnabled) {
                                val faces = result.get(CaptureResult.STATISTICS_FACES)
                                val activeRect = sensorActiveRect
                                if (!faces.isNullOrEmpty() && activeRect != null) {
                                    val detected = faces.mapNotNull { face ->
                                        convertFaceToNormalized(face, activeRect)
                                    }
                                    if (detected.isNotEmpty()) {
                                        // このコールバックはカメラのスレッドで呼ばれる。
                                        // 受け取り側は PreviewView (メインスレッド専用) を触るため、
                                        // メインスレッドに載せ替えて通知する
                                        mainExecutor.execute {
                                            if (faceDetectionEnabled) onFacesDetected?.invoke(detected)
                                        }
                                    }
                                }
                            }
                        }
                    })
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

                // センサー情報取得 (顔座標変換に必須)
                querySensorInfo()

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
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        // マニュアルフォーカス中の場合、AF_MODEをCONTINUOUS_PICTUREに戻す
        // (setManualFocusDistanceでAF_MODE_OFFにされている可能性がある)
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            .clearCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE)
            .build()
        camera2Control.setCaptureRequestOptions(options)

        awaitingFocusDistance = true
        awaitingFocusResult = true
        focusScanStarted = false
        val point = previewView.meteringPointFactory.createPoint(x, y, METERING_POINT_SIZE)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3500, TimeUnit.MILLISECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
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

        val contentValues = MediaStoreSaver.newImageValues(filename, "image/jpeg")

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
     * RAW+JPEG 撮影。撮影中は CameraX のバインドを外すので、
     * 終わったら onFinished (メインスレッド) でカメラを付け直すこと。
     * 付け直しは呼び出し側が「そのとき画面にある」PreviewView で行う
     * (撮影開始時の PreviewView を持ち続けると、画面を離れた後に古い画面へ付け直してしまう)。
     */
    fun captureRawPhoto(
        manualSettings: CameraSettings?,
        onFinished: () -> Unit,
    ) {
        val cameraId = currentCameraId ?: run {
            onError?.invoke("Camera ID not available")
            return
        }
        val characteristics = currentCharacteristics ?: run {
            onError?.invoke("Camera characteristics not available")
            return
        }

        if (rawCaptureHelper == null) {
            rawCaptureHelper = RawCaptureHelper(context)
        }

        val exposureParams = if (manualSettings != null && !manualSettings.autoExposure) {
            RawCaptureHelper.ExposureParams(
                iso = manualSettings.iso,
                exposureTimeNs = manualSettings.shutterSpeedNs,
                // AF が ON のときは、プレビューの AF が合わせた距離を使う
                // (手動フォーカス用の値は初期値 0 = 無限遠なので、そのまま使うとピンボケになる)
                focusDistance = if (manualSettings.autoFocus) lastFocusDist else manualSettings.focusDistance,
                awbMode = whiteBalanceToAwbMode(manualSettings.whiteBalance),
            )
        } else {
            RawCaptureHelper.ExposureParams(
                iso = lastIso,
                exposureTimeNs = lastExposureTimeNs,
                focusDistance = lastFocusDist,
                awbMode = lastAwbMode,
            )
        }

        val jpegOrientation = computeJpegOrientation(characteristics)

        cameraProvider?.unbindAll()

        rawCaptureHelper!!.capture(
            cameraId = cameraId,
            characteristics = characteristics,
            exposureParams = exposureParams,
            jpegOrientation = jpegOrientation,
            onResult = { result ->
                mainExecutor.execute {
                    onFinished()
                    onRawCaptureComplete?.invoke(result)
                }
            },
            onError = { msg ->
                mainExecutor.execute {
                    onFinished()
                    onError?.invoke(msg)
                }
            },
        )
    }

    private fun whiteBalanceToAwbMode(mode: WhiteBalanceMode): Int {
        return when (mode) {
            WhiteBalanceMode.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            WhiteBalanceMode.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            WhiteBalanceMode.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            WhiteBalanceMode.TUNGSTEN -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            WhiteBalanceMode.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            WhiteBalanceMode.SHADE -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        }
    }

    /**
     * Camera2 の JPEG_ORIENTATION に設定する値を求める。
     * センサー自体の取り付け向き (SENSOR_ORIENTATION) と端末の回転を合成する。
     * targetRotation は画面の回転 (端末の回転と逆向き) なので、背面カメラでは引き算になる。
     */
    private fun computeJpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorDeg = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: sensorOrientation
        val surfaceDeg = surfaceRotationToDegrees(imageCapture?.targetRotation ?: Surface.ROTATION_0)
        return if (isFrontCamera) {
            (sensorDeg + surfaceDeg) % 360
        } else {
            (sensorDeg - surfaceDeg + 360) % 360
        }
    }

    private fun surfaceRotationToDegrees(surfaceRotation: Int): Int {
        return when (surfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * バーストモード用: 1枚撮影して保存し、保存が終わるまで待つ。
     * 連番はタイムスタンプ + index で衝突を回避。
     *
     * @param burstId バースト開始時のタイムスタンプ (同一バースト内で共通)
     * @param index バースト内の連番 (1始まり)
     * @return 保存先 URI。失敗時は null
     */
    suspend fun captureBurstPhoto(burstId: String, index: Int): String? {
        val capture = imageCapture ?: run {
            onError?.invoke("Camera not ready")
            return null
        }

        val filename = "GoodCam_Burst_${burstId}_${index.toString().padStart(3, '0')}.jpg"

        val contentValues = MediaStoreSaver.newImageValues(filename, "image/jpeg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues,
        ).build()

        return suspendCancellableCoroutine<String?> { cont ->
            capture.takePicture(
                outputOptions,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        cont.resume(output.savedUri?.toString())
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Burst capture failed (index=$index)", exception)
                        // バーストでは個別フレームのエラーは無視して続行
                        cont.resume(null)
                    }
                },
            )
        }
    }

    /**
     * 1枚撮影し、向きを補正した Bitmap として返す (ファイルには保存しない)。
     * ナイトモード・HDR・自動補正付き撮影で使う。
     * デコードは専用スレッドで行うのでメインスレッドを止めない。
     */
    suspend fun captureFrame(): Bitmap {
        val capture = imageCapture ?: throw IllegalStateException("Camera not ready")
        return suspendCancellableCoroutine<Bitmap> { cont ->
            capture.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    // OutOfMemoryError も呼び出し元に伝えるため Throwable で受ける
                    val result = runCatching { imageProxyToBitmap(image) }
                    image.close()
                    val bitmap = result.getOrElse {
                        cont.resumeWithException(it)
                        return
                    }
                    if (bitmap != null) {
                        // 呼び出し側が既にキャンセルされていたら、受け取り手のいない Bitmap を解放する
                        cont.resume(bitmap) { _ -> bitmap.recycle() }
                    } else {
                        cont.resumeWithException(IOException("Failed to decode captured image"))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    /** 露出補正 1段あたりの EV 値 (未対応なら 0) */
    val exposureCompensationStepEv: Float
        get() {
            val state = camera?.cameraInfo?.exposureState ?: return 0f
            if (!state.isExposureCompensationSupported) return 0f
            return state.exposureCompensationStep.toFloat()
        }

    /** 露出補正インデックスの範囲 */
    val exposureCompensationRange: IntRange
        get() {
            val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return 0..0
            return range.lower..range.upper
        }

    /** 現在の露出補正インデックス */
    val exposureCompensationIndex: Int
        get() = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0

    /**
     * 露出補正を設定し、AE が新しい値で落ち着くまで待つ。
     * 設定が後から上書きされた等で失敗しても、待ちは解除する。
     */
    suspend fun setExposureCompensationAndAwait(index: Int) {
        val cam = camera ?: return
        val future = cam.cameraControl.setExposureCompensationIndex(index)
        suspendCancellableCoroutine<Unit> { cont ->
            future.addListener({ cont.resume(Unit) }, mainExecutor)
        }
    }

    /**
     * Bitmap を JPEG として MediaStore に保存する。
     */
    fun saveBitmapToGallery(bitmap: Bitmap, prefix: String = "GoodCam"): String? {
        @Suppress("SpellCheckingInspection")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return try {
            MediaStoreSaver.saveJpeg(context, bitmap, "${prefix}_$timestamp.jpg")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo", e)
            null
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // 回転補正
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "ImageProxy to Bitmap conversion failed", e)
            null
        }
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
     * Proモード: ISO感度を設定する。
     * AE_MODE を OFF にし、手動で SENSOR_SENSITIVITY を設定する。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setIso(iso: Int) {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .build()
        camera2Control.setCaptureRequestOptions(options)
        Log.d(TAG, "Pro: ISO=$iso")
    }

    /**
     * Proモード: シャッタースピードを設定する。
     * AE_MODE を OFF にし、手動で SENSOR_EXPOSURE_TIME を設定する。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setShutterSpeed(ns: Long) {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            .build()
        camera2Control.setCaptureRequestOptions(options)
        Log.d(TAG, "Pro: shutterSpeed=${ns}ns")
    }

    /**
     * Proモード: ホワイトバランスを設定する。
     * Camera2のビルトインAWBプリセットにマッピングする。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun setWhiteBalance(mode: WhiteBalanceMode) {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val awbMode = when (mode) {
            WhiteBalanceMode.AUTO -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            WhiteBalanceMode.DAYLIGHT -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            WhiteBalanceMode.CLOUDY -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            WhiteBalanceMode.TUNGSTEN -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            WhiteBalanceMode.FLUORESCENT -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            WhiteBalanceMode.SHADE -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        }
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            .build()
        camera2Control.setCaptureRequestOptions(options)
        Log.d(TAG, "Pro: WB=${mode.label} (awbMode=$awbMode)")
    }

    /**
     * Proモード: 自動露出を有効に戻す。
     * AE_MODE を ON_AUTO_FLASH に戻し、手動のISO/SSを解除する。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun enableAutoExposure() {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON,
            )
            .clearCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY)
            .clearCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME)
            .build()
        camera2Control.setCaptureRequestOptions(options)
        Log.d(TAG, "Pro: auto exposure restored")
    }

    /**
     * Proモード: オートフォーカスを有効に戻す。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun enableAutoFocus() {
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
        Log.d(TAG, "Pro: auto focus restored")
    }

    /**
     * Proモード終了: 全てのCamera2手動設定をクリアしてAutoに戻す。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun resetToAuto() {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON,
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO,
            )
            .clearCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY)
            .clearCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME)
            .clearCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE)
            .build()
        camera2Control.setCaptureRequestOptions(options)
        Log.d(TAG, "Pro: reset to full auto")
    }

    /**
     * 露出補正インデックスを設定する。
     */
    fun setExposureCompensation(index: Int) {
        val cam = camera ?: return
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    /**
     * 顔検出結果のコールバック転送を有効にする。
     * Camera2の FACE_DETECT_MODE はセッション開始時に設定済みなので、
     * このメソッドはコールバック転送フラグのみ切り替える。
     */
    fun enableFaceDetection() {
        faceDetectionEnabled = true
        Log.d(TAG, "Face detection forwarding ON")
    }

    /**
     * 顔検出結果のコールバック転送を無効にする。
     */
    fun disableFaceDetection() {
        faceDetectionEnabled = false
        Log.d(TAG, "Face detection forwarding OFF")
    }

    /**
     * 検出された顔の中心にフォーカスを合わせる。
     * プレビュー座標 (0..1) で受け取り、PreviewView座標に変換してメータリングを実行。
     */
    fun focusOnFace(previewView: PreviewView, viewNormX: Float, viewNormY: Float) {
        val cam = camera ?: return
        val viewX = viewNormX * previewView.width
        val viewY = viewNormY * previewView.height
        val point = previewView.meteringPointFactory.createPoint(viewX, viewY, FACE_METERING_POINT_SIZE)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2000, TimeUnit.MILLISECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    /**
     * センサー情報を取得・保存する (顔座標変換に必須)。
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun querySensorInfo() {
        val cam = camera ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)

            val camId = camera2Info.cameraId
            currentCameraId = camId
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            currentCharacteristics = cameraManager.getCameraCharacteristics(camId)

            sensorActiveRect = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
            )
            sensorOrientation = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_ORIENTATION,
            ) ?: 0
            val maxFaces = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT,
            ) ?: 0
            Log.d(TAG, "Sensor: orientation=$sensorOrientation, activeRect=$sensorActiveRect, " +
                "maxFaces=$maxFaces, front=$isFrontCamera")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query sensor info", e)
        }
    }

    /**
     * Camera2の Face をプレビュー座標 (0..1) の DetectedFace に変換する。
     *
     * Camera2の顔座標はセンサーのアクティブ領域座標系。
     * PreviewView は端末の向きに合わせて画像を回転表示するため、
     * センサー回転角度に応じた座標変換が必要。
     * フロントカメラの場合はさらにX軸ミラーリングが必要。
     */
    private fun convertFaceToNormalized(face: Face, activeRect: Rect): DetectedFace? {
        val bounds = face.bounds
        if (bounds.isEmpty) return null
        val sensorW = activeRect.width().toFloat()
        val sensorH = activeRect.height().toFloat()
        if (sensorW <= 0f || sensorH <= 0f) return null

        // センサー正規化座標 (0..1)
        val sx = ((bounds.left + bounds.right) / 2f - activeRect.left) / sensorW
        val sy = ((bounds.top + bounds.bottom) / 2f - activeRect.top) / sensorH
        val sw = bounds.width().toFloat() / sensorW
        val sh = bounds.height().toFloat() / sensorH

        // センサー回転角度とディスプレイ回転を合成してプレビュー座標に変換
        // SENSOR_ORIENTATION は「出力画像を正立させるための CW 回転角度」
        // displayRotationDegrees は端末の現在の向き (0=縦, 90=横右, etc.)
        // 実効回転 = (sensorOrientation - displayRotation + 360) % 360
        val effectiveRotation = (sensorOrientation - displayRotationDegrees + 360) % 360
        var vx: Float
        var vy: Float
        var vw: Float
        var vh: Float
        when (effectiveRotation) {
            90 -> {
                vx = 1f - sy; vy = sx; vw = sh; vh = sw
            }
            180 -> {
                vx = 1f - sx; vy = 1f - sy; vw = sw; vh = sh
            }
            270 -> {
                vx = sy; vy = 1f - sx; vw = sh; vh = sw
            }
            else -> {
                vx = sx; vy = sy; vw = sw; vh = sh
            }
        }

        // フロントカメラはプレビューがミラーリングされている。
        // ミラー軸はディスプレイ回転に依存する:
        //   縦位置 (0°/180°) → X軸ミラー, 横位置 (90°/270°) → Y軸ミラー
        if (isFrontCamera) {
            if (displayRotationDegrees == 90 || displayRotationDegrees == 270) {
                vy = 1f - vy
            } else {
                vx = 1f - vx
            }
        }

        return DetectedFace(
            centerX = vx.coerceIn(0f, 1f),
            centerY = vy.coerceIn(0f, 1f),
            width = vw,
            height = vh,
            score = face.score,
        )
    }

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun queryCameraCapabilities() {
        val cam = camera ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val minFocus = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
            ) ?: 0f
            val evRangeRaw = cam.cameraInfo.exposureState.exposureCompensationRange
            val evMin: Int = evRangeRaw.lower
            val evMax: Int = evRangeRaw.upper

            // ISO レンジ
            val sensorRange = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE,
            )
            val isoRange = if (sensorRange != null) {
                sensorRange.lower..sensorRange.upper
            } else {
                100..3200
            }
            // 標準ISO値に絞り込み
            val standardIsos = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
            val supportedIsos = standardIsos.filter { it in isoRange }
                .ifEmpty { listOf(100, 200, 400, 800, 1600, 3200) }

            // シャッタースピードレンジ
            val exposureTimeRange = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE,
            )
            val shutterSpeedRange = if (exposureTimeRange != null) {
                exposureTimeRange.lower..exposureTimeRange.upper
            } else {
                1_000_000L..1_000_000_000L
            }

            val supportsRaw = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            )?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

            val capabilities = CameraCapabilities(
                isoRange = isoRange,
                shutterSpeedRangeNs = shutterSpeedRange,
                minFocusDistance = minFocus,
                supportsRaw = supportsRaw,
                supportedIsos = supportedIsos,
                exposureCompensationRange = evMin..evMax,
            )
            Log.d(TAG, "Camera capabilities: minFocusDistance=$minFocus, evRange=$evMin..$evMax, " +
                "isoRange=$isoRange, shutterRange=$shutterSpeedRange, raw=$supportsRaw")
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
                val rotation = when (orientation) {
                    in 315..359, in 0 until 45 -> Surface.ROTATION_0
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                imageCapture?.targetRotation = rotation
                displayRotationDegrees = when (rotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
            }
        }
        orientationListener?.enable()
    }

    fun release() {
        orientationListener?.disable()
        orientationListener = null
        faceDetectionEnabled = false
        cameraProvider?.unbindAll()
        captureExecutor.shutdown()
    }
}

/**
 * 検出された顔の情報 (正規化座標 0..1)
 */
data class DetectedFace(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val score: Int,
)
