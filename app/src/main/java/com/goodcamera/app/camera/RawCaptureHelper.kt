package com.goodcamera.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

class RawCaptureHelper(private val context: Context) {

    companion object {
        private const val TAG = "RawCaptureHelper"
        private const val CAMERA_OPEN_TIMEOUT_MS = 2500L
        private const val SESSION_CONFIGURE_TIMEOUT_MS = 3000L
        private const val CAPTURE_TIMEOUT_MS = 5000L
    }

    data class ExposureParams(
        val iso: Int = 200,
        val exposureTimeNs: Long = 33_333_333L,
        val focusDistance: Float = 0f,
        val awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
        val useAutoExposure: Boolean = true,
    )

    data class RawCaptureResult(
        val jpegUri: String?,
        val dngUri: String?,
    )

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val openSemaphore = Semaphore(1)

    fun capture(
        cameraId: String,
        characteristics: CameraCharacteristics,
        exposureParams: ExposureParams,
        rotation: Int,
        onResult: (RawCaptureResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        val handlerThread = HandlerThread("RawCapture").apply { start() }
        val handler = Handler(handlerThread.looper)

        handler.post {
            try {
                val result = captureInternal(
                    cameraId, characteristics, exposureParams, rotation, handler,
                )
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "RAW capture failed", e)
                onError("RAW capture failed: ${e.message}")
            } finally {
                handlerThread.quitSafely()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun captureInternal(
        cameraId: String,
        characteristics: CameraCharacteristics,
        exposureParams: ExposureParams,
        rotation: Int,
        handler: Handler,
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
            cameraDevice = openCamera(cameraId, handler)
            val session = createSession(cameraDevice, rawReader, jpegReader, handler)
            val captureResult = submitCapture(
                session, cameraDevice, rawReader, jpegReader,
                characteristics, exposureParams, rotation, handler,
            )

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            val jpegUri = saveJpeg(jpegReader, timestamp)
            val dngUri = saveDng(rawReader, characteristics, captureResult, rotation, timestamp)

            return RawCaptureResult(jpegUri = jpegUri, dngUri = dngUri)
        } finally {
            cameraDevice?.close()
            rawReader.close()
            jpegReader.close()
        }
    }

    @Suppress("MissingPermission")
    private fun openCamera(cameraId: String, handler: Handler): CameraDevice {
        val latch = CountDownLatch(1)
        var device: CameraDevice? = null
        var error: String? = null

        if (!openSemaphore.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Timed out waiting for camera semaphore")
        }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    latch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    error = "Camera disconnected"
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, errorCode: Int) {
                    camera.close()
                    error = "Camera open error: $errorCode"
                    latch.countDown()
                }
            }, handler)

            if (!latch.await(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("Camera open timed out")
            }
        } finally {
            openSemaphore.release()
        }

        error?.let { throw IllegalStateException(it) }
        return device ?: throw IllegalStateException("Camera device is null")
    }

    private fun createSession(
        device: CameraDevice,
        rawReader: ImageReader,
        jpegReader: ImageReader,
        handler: Handler,
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
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                context.mainExecutor,
                stateCallback,
            )
            device.createCaptureSession(sessionConfig)
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(rawReader.surface, jpegReader.surface),
                stateCallback,
                handler,
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
        characteristics: CameraCharacteristics,
        exposureParams: ExposureParams,
        rotation: Int,
        handler: Handler,
    ): TotalCaptureResult {
        val latch = CountDownLatch(1)
        var captureResult: TotalCaptureResult? = null

        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader.surface)
            addTarget(jpegReader.surface)

            set(CaptureRequest.JPEG_ORIENTATION, rotation)

            if (exposureParams.useAutoExposure) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AWB_MODE, exposureParams.awbMode)
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, exposureParams.iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureParams.exposureTimeNs)
                set(CaptureRequest.CONTROL_AWB_MODE, exposureParams.awbMode)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, exposureParams.focusDistance)
            }

            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)

            set(CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_OFF)
        }

        if (exposureParams.useAutoExposure) {
            runAePrecapture(session, device, rawReader, jpegReader, handler)
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
        }, handler)

        if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Capture timed out")
        }
        return captureResult ?: throw IllegalStateException("CaptureResult is null")
    }

    private fun runAePrecapture(
        session: CameraCaptureSession,
        device: CameraDevice,
        rawReader: ImageReader,
        jpegReader: ImageReader,
        handler: Handler,
    ) {
        val precaptureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(jpegReader.surface)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        val latch = CountDownLatch(1)
        session.capture(precaptureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                latch.countDown()
            }
        }, handler)
        latch.await(1000, TimeUnit.MILLISECONDS)

        Thread.sleep(300)
    }

    private fun saveJpeg(jpegReader: ImageReader, timestamp: String): String? {
        val image = jpegReader.acquireLatestImage() ?: return null
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val filename = "GoodCam_$timestamp.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DCIM + "/GoodCamera")
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues,
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
            }
            Log.d(TAG, "JPEG saved: $uri")
            uri.toString()
        } finally {
            image.close()
        }
    }

    private fun saveDng(
        rawReader: ImageReader,
        characteristics: CameraCharacteristics,
        captureResult: TotalCaptureResult,
        rotation: Int,
        timestamp: String,
    ): String? {
        val image = rawReader.acquireLatestImage() ?: return null
        return try {
            val filename = "GoodCam_$timestamp.dng"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DCIM + "/GoodCamera")
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues,
            ) ?: return null

            context.contentResolver.openOutputStream(uri)?.use { out ->
                val dngCreator = DngCreator(characteristics, captureResult)
                dngCreator.setOrientation(rotationToExifOrientation(rotation))
                dngCreator.writeImage(out, image)
                dngCreator.close()
            }
            Log.d(TAG, "DNG saved: $uri")
            uri.toString()
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
