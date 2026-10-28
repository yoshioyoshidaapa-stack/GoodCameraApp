package com.goodcamera.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import com.goodcamera.app.util.MediaStoreSaver
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

class RawCaptureHelper(private val context: Context) {

    companion object {
        private const val TAG = "RawCaptureHelper"
        private const val CAMERA_OPEN_TIMEOUT_MS = 2500L
        private const val SESSION_CONFIGURE_TIMEOUT_MS = 3000L
        private const val CAPTURE_TIMEOUT_MS = 5000L
        private const val IMAGE_TIMEOUT_MS = 3000L
    }

    data class ExposureParams(
        val iso: Int = 200,
        val exposureTimeNs: Long = 33_333_333L,
        val focusDistance: Float = 0f,
        val awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    )

    data class RawCaptureResult(
        val jpegUri: String?,
        val dngUri: String?,
    )

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val openSemaphore = Semaphore(1)

    /**
     * RAW+JPEG を撮影して保存する。
     *
     * 待ち合わせ (CountDownLatch.await) を行うワーカースレッドと、
     * Camera2 のコールバックを受け取るスレッドを分けている。
     * 同じスレッドにすると、待っている間コールバックが一切実行されず必ずタイムアウトする。
     *
     * @param jpegOrientation JPEG/DNG に設定する向き (センサー向きと端末回転を合成済みの度数)
     */
    fun capture(
        cameraId: String,
        characteristics: CameraCharacteristics,
        exposureParams: ExposureParams,
        jpegOrientation: Int,
        onResult: (RawCaptureResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val workerThread = HandlerThread("RawCaptureWorker").apply { start() }
        val callbackThread = HandlerThread("RawCaptureCallback").apply { start() }
        val callbackHandler = Handler(callbackThread.looper)

        Handler(workerThread.looper).post {
            try {
                val result = captureInternal(
                    cameraId, characteristics, exposureParams, jpegOrientation, callbackHandler,
                )
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "RAW capture failed", e)
                onError("RAW capture failed: ${e.message}")
            } finally {
                callbackThread.quitSafely()
                workerThread.quitSafely()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun captureInternal(
        cameraId: String,
        characteristics: CameraCharacteristics,
        exposureParams: ExposureParams,
        jpegOrientation: Int,
        callbackHandler: Handler,
    ): RawCaptureResult {
        val rawSize = getLargestRawSize(characteristics)
            ?: throw IllegalStateException("No RAW output size available")
        val jpegSize = getLargestJpegSize(characteristics)

        val rawReader = ImageReader.newInstance(
            rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 1,
        )
        val jpegReader = ImageReader.newInstance(
            jpegSize.width, jpegSize.height, ImageFormat.JPEG, 1,
        )

        var cameraDevice: CameraDevice? = null
        try {
            cameraDevice = openCamera(cameraId, callbackHandler)
            val session = createSession(cameraDevice, rawReader, jpegReader, callbackHandler)

            // onCaptureCompleted は画像データより先に届くことがあるため、
            // 両方の ImageReader に画像が届くまで待ってから保存する
            val rawReady = CountDownLatch(1)
            val jpegReady = CountDownLatch(1)
            rawReader.setOnImageAvailableListener({ rawReady.countDown() }, callbackHandler)
            jpegReader.setOnImageAvailableListener({ jpegReady.countDown() }, callbackHandler)

            val captureResult = submitCapture(
                session, cameraDevice, rawReader, jpegReader,
                exposureParams, jpegOrientation, callbackHandler,
            )

            val hasRaw = rawReady.await(IMAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val hasJpeg = jpegReady.await(IMAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!hasRaw) Log.w(TAG, "RAW image did not arrive in time")
            if (!hasJpeg) Log.w(TAG, "JPEG image did not arrive in time")

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            val jpegUri = if (hasJpeg) saveJpeg(jpegReader, timestamp) else null
            val dngUri = if (hasRaw) {
                saveDng(rawReader, characteristics, captureResult, jpegOrientation, timestamp)
            } else {
                null
            }
            if (jpegUri == null && dngUri == null) {
                throw IllegalStateException("No image was received")
            }

            return RawCaptureResult(jpegUri = jpegUri, dngUri = dngUri)
        } finally {
            cameraDevice?.close()
            rawReader.close()
            jpegReader.close()
        }
    }

    @Suppress("MissingPermission")
    private fun openCamera(cameraId: String, callbackHandler: Handler): CameraDevice {
        val latch = CountDownLatch(1)
        val lock = Any()
        var device: CameraDevice? = null
        var error: String? = null
        // タイムアウト後に onOpened が遅れて届いた場合、開いたカメラを閉じるためのフラグ
        var abandoned = false

        if (!openSemaphore.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Timed out waiting for camera semaphore")
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val keep = synchronized(lock) {
                        if (!abandoned) device = camera
                        !abandoned
                    }
                    if (!keep) camera.close()
                    latch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    synchronized(lock) { error = "Camera disconnected" }
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, errorCode: Int) {
                    camera.close()
                    synchronized(lock) { error = "Camera open error: $errorCode" }
                    latch.countDown()
                }
            }, callbackHandler)

            if (!latch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                synchronized(lock) {
                    abandoned = true
                    device?.close()
                    device = null
                }
                throw IllegalStateException("Camera open timed out")
            }
        } finally {
            openSemaphore.release()
        }

        synchronized(lock) {
            error?.let { throw IllegalStateException(it) }
            return device ?: throw IllegalStateException("Camera device is null")
        }
    }

    private fun createSession(
        device: CameraDevice,
        rawReader: ImageReader,
        jpegReader: ImageReader,
        callbackHandler: Handler,
    ): CameraCaptureSession {
        val latch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        var error: String? = null

        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                latch.countDown()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                error = "Session configuration failed"
                latch.countDown()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputs = listOf(
                OutputConfiguration(rawReader.surface),
                OutputConfiguration(jpegReader.surface),
            )
            val callbackExecutor = Executor { command -> callbackHandler.post(command) }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                callbackExecutor,
                stateCallback,
            )
            device.createCaptureSession(sessionConfig)
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(rawReader.surface, jpegReader.surface),
                stateCallback,
                callbackHandler,
            )
        }

        if (!latch.await(SESSION_CONFIGURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Session configure timed out")
        }
        error?.let { throw IllegalStateException(it) }
        return session ?: throw IllegalStateException("Session is null")
    }

    private fun submitCapture(
        session: CameraCaptureSession,
        device: CameraDevice,
        rawReader: ImageReader,
        jpegReader: ImageReader,
        exposureParams: ExposureParams,
        jpegOrientation: Int,
        callbackHandler: Handler,
    ): TotalCaptureResult {
        val latch = CountDownLatch(1)
        var captureResult: TotalCaptureResult? = null

        // 露出・フォーカスはプレビュー時の値 (または Pro モードの手動値) で固定して撮影する
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader.surface)
            addTarget(jpegReader.surface)

            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, exposureParams.iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureParams.exposureTimeNs)
            set(CaptureRequest.CONTROL_AWB_MODE, exposureParams.awbMode)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, exposureParams.focusDistance)

            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)

            set(CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_OFF)
        }

        session.capture(requestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                captureResult = result
                latch.countDown()
            }

            override fun onCaptureFailed(
                s: CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure,
            ) {
                Log.e(TAG, "Capture failed: reason=${failure.reason}")
                latch.countDown()
            }
        }, callbackHandler)

        if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Capture timed out")
        }
        return captureResult ?: throw IllegalStateException("CaptureResult is null")
    }

    private fun saveJpeg(jpegReader: ImageReader, timestamp: String): String? {
        val image = jpegReader.acquireLatestImage() ?: return null
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val uri = MediaStoreSaver.save(context, "GoodCam_$timestamp.jpg", "image/jpeg") { out ->
                out.write(bytes)
            }
            Log.d(TAG, "JPEG saved: $uri")
            uri
        } finally {
            image.close()
        }
    }

    private fun saveDng(
        rawReader: ImageReader,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        jpegOrientation: Int,
        timestamp: String,
    ): String? {
        val image = rawReader.acquireLatestImage() ?: return null
        return try {
            val uri = MediaStoreSaver.save(context, "GoodCam_$timestamp.dng", "image/x-adobe-dng") { out ->
                DngCreator(characteristics, captureResult).use { dngCreator ->
                    dngCreator.setOrientation(rotationToExifOrientation(jpegOrientation))
                    dngCreator.writeImage(out, image)
                }
            }
            Log.d(TAG, "DNG saved: $uri")
            uri
        } finally {
            image.close()
        }
    }

    private fun getLargestRawSize(characteristics: CameraCharacteristics): Size? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
        return sizes?.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun getLargestJpegSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)
        return sizes?.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(4032, 3024)
    }

    private fun rotationToExifOrientation(rotation: Int): Int {
        return when (rotation) {
            90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> android.media.ExifInterface.ORIENTATION_NORMAL
        }
    }
}
