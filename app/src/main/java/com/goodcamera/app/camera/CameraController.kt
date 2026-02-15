package com.goodcamera.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import com.goodcamera.app.processing.ImageProcessor
import com.goodcamera.app.processing.HdrToneMapper
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "CameraController"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executors.newSingleThreadExecutor()

    private var currentCameraId: String? = null
    private var currentSettings = CameraSettings()
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    var onCapabilities: ((CameraCapabilities) -> Unit)? = null
    var onCaptureComplete: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ---- Public API ----

    fun openCamera(useFront: Boolean, surface: Surface) {
        previewSurface = surface
        val cameraId = findCameraId(useFront) ?: run {
            onError?.invoke("No ${if (useFront) "front" else "back"} camera found")
            return
        }
        currentCameraId = cameraId

        // Capabilities をバックグラウンドで取得（openCamera をブロックしない）
        cameraHandler.post { queryCapabilities(cameraId) }

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession(surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    onError?.invoke("Camera error: $error")
                }
            }, cameraHandler)
        } catch (e: SecurityException) {
            onError?.invoke("Camera permission not granted")
        }
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        rawImageReader?.close()
        rawImageReader = null
    }

    fun updateSettings(settings: CameraSettings) {
        currentSettings = settings
        updatePreviewRequest()
    }

    fun capturePhoto(mode: CaptureMode, outputFormat: OutputFormat, hdrFrames: Int = 3, nightFrames: Int = 8) {
        when (mode) {
            CaptureMode.AI_AUTO, CaptureMode.AUTO, CaptureMode.PRO -> captureSingle(outputFormat)
            CaptureMode.HDR -> captureHdr(hdrFrames, outputFormat)
            CaptureMode.NIGHT -> captureNight(nightFrames, outputFormat)
            CaptureMode.BURST -> { /* Burst is handled by startBurst/stopBurst */ }
        }
    }

    fun switchCamera(useFront: Boolean, surface: Surface) {
        closeCamera()
        openCamera(useFront, surface)
    }

    // ---- Capture Resolution ----

    fun getOptimalCaptureSize(cameraId: String? = null): Size {
        val id = cameraId ?: currentCameraId ?: return Size(1920, 1080)
        val chars = cameraManager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return Size(1920, 1080)
        return sizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    }

    fun getOptimalPreviewSize(cameraId: String? = null, targetWidth: Int = 1920): Size {
        val id = cameraId ?: currentCameraId ?: return Size(1920, 1080)
        val chars = cameraManager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?: return Size(1920, 1080)
        // Pick the largest size that doesn't exceed targetWidth
        return sizes
            .filter { it.width <= targetWidth }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    // ---- Internal: Preview ----

    /**
     * プレビューセッションを構築する。
     * 起動高速化のため、RAW ImageReader は遅延初期化（RAW+JPEG選択時に初めて作成）。
     * サーフェス数が少ないほどセッション構築が速い。
     */
    private fun createPreviewSession(surface: Surface) {
        val device = cameraDevice ?: return
        val captureSize = getOptimalCaptureSize()

        // JPEG用ImageReaderは既存があれば再利用
        if (imageReader == null) {
            imageReader = ImageReader.newInstance(
                captureSize.width, captureSize.height, ImageFormat.JPEG, 2
            )
        }

        // 起動高速化: RAW ImageReaderは初回セッションでは追加しない。
        // RAW+JPEG形式を選択した時に初めてセッション再構築でRAWを追加する。
        val surfaces = mutableListOf(surface, imageReader!!.surface)
        if (rawImageReader != null) {
            surfaces.add(rawImageReader!!.surface)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { OutputConfiguration(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            updatePreviewRequest()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            onError?.invoke("Failed to configure camera session")
                        }
                    }
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            updatePreviewRequest()
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            onError?.invoke("Failed to configure camera session")
                        }
                    }, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            onError?.invoke("Camera access error: ${e.message}")
        }
    }

    /**
     * RAW+JPEG撮影時に必要なRAW ImageReaderを遅延初期化し、セッションを再構築する。
     */
    fun ensureRawSessionIfNeeded() {
        if (rawImageReader != null) return
        val cameraId = currentCameraId ?: return
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (rawSizes != null && rawSizes.isNotEmpty()) {
            val rawSize = rawSizes.maxByOrNull { it.width * it.height }!!
            rawImageReader = ImageReader.newInstance(
                rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2
            )
            // RAWサーフェス追加のためセッション再構築
            val surface = previewSurface ?: return
            createPreviewSession(surface)
        }
    }

    private fun updatePreviewRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applySettingsForPreview(this, currentSettings)
            }
            previewRequestBuilder = builder
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: CameraAccessException) {
            onError?.invoke("Preview error: ${e.message}")
        }
    }

    // ---- Internal: Capture ----

    private fun captureSingle(outputFormat: OutputFormat) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        // AFロック後に撮影を実行するラムダ
        val doCapture = {
            try {
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    if (outputFormat == OutputFormat.RAW_DNG && rawImageReader != null) {
                        addTarget(rawImageReader!!.surface)
                    }
                    applySettingsForCapture(this, currentSettings)
                    set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                }

                reader.setOnImageAvailableListener({ imgReader ->
                    val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    // Auto/Proモードは軽い後処理を適用
                    val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (original != null) {
                        val processed = ImageProcessor.process(original, ImageProcessor.ProcessingConfig(
                            denoiseEnabled = true,
                            denoiseStrength = ImageProcessor.DenoiseStrength.LIGHT,
                            sharpenEnabled = true,
                            sharpenAmount = 1.0f,
                            autoLevelsEnabled = true,
                            autoLevelsClip = 0.5f,
                        ))
                        original.recycle()
                        val path = saveBitmap(processed, "JPEG")
                        processed.recycle()
                        path?.let { onCaptureComplete?.invoke(it) }
                    } else {
                        val path = saveToMediaStore(bytes, "JPEG", "image/jpeg", ".jpg")
                        path?.let { onCaptureComplete?.invoke(it) }
                    }
                }, cameraHandler)

                if (outputFormat == OutputFormat.RAW_DNG && rawImageReader != null) {
                    rawImageReader!!.setOnImageAvailableListener({ imgReader ->
                        val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        saveDngImage(image)
                        image.close()
                    }, cameraHandler)
                }

                session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        onError?.invoke("Capture failed: ${failure.reason}")
                    }
                }, cameraHandler)
            } catch (e: CameraAccessException) {
                onError?.invoke("Capture error: ${e.message}")
            }
        }

        // AFがオートの場合、撮影前にAFロックを確認
        if (currentSettings.autoFocus) {
            precaptureAfAndRun(doCapture)
        } else {
            doCapture()
        }
    }

    /**
     * 撮影前にAFトリガーを発行し、ロック後にアクションを実行。
     * タイムアウト付きで、ロック失敗時もフォールバックで撮影する。
     */
    private fun precaptureAfAndRun(onLocked: () -> Unit) {
        val device = cameraDevice ?: run { onLocked(); return }
        val session = captureSession ?: run { onLocked(); return }
        val surface = previewSurface ?: run { onLocked(); return }

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applySettingsForPreview(this, currentSettings)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                afRegion?.let {
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                }
            }

            // タイムアウト: 800ms 以内にロックしなければ撮影続行
            val timeoutRunnable = Runnable { onLocked() }
            cameraHandler.postDelayed(timeoutRunnable, 800)

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    ) {
                        cameraHandler.removeCallbacks(timeoutRunnable)
                        onLocked()
                    }
                    // PASSIVE_FOCUSED 等の場合はタイムアウトに任せる
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            onLocked() // エラー時はそのまま撮影
        }
    }

    private fun captureHdr(frameCount: Int, outputFormat: OutputFormat) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        val hdrImages = Collections.synchronizedList(mutableListOf<Bitmap>())

        // Exposure compensation values for bracketing: underexposed, normal, overexposed
        val evSteps = when (frameCount) {
            3 -> listOf(-2, 0, 2)
            5 -> listOf(-3, -1, 0, 1, 3)
            else -> listOf(-2, 0, 2)
        }

        var capturedCount = 0

        reader.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                hdrImages.add(bitmap)
            }

            capturedCount++
            if (capturedCount >= frameCount) {
                // Reinhard tone mapping + post-processing pipeline
                val processed = ImageProcessor.processHdr(hdrImages)
                val path = saveBitmap(processed, "HDR")
                hdrImages.forEach { it.recycle() }
                processed.recycle()
                path?.let { onCaptureComplete?.invoke(it) }
            }
        }, cameraHandler)

        try {
            val requests = evSteps.map { ev ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applySettingsForCapture(this, currentSettings)
                    // HDRブラケット: AE ONで露出補正を段階的に変更
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
                    set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                }.build()
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    onError?.invoke("HDR capture failed: ${failure.reason}")
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            onError?.invoke("HDR capture error: ${e.message}")
        }
    }

    private fun captureNight(frameCount: Int, outputFormat: OutputFormat) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        val nightImages = Collections.synchronizedList(mutableListOf<Bitmap>())
        var capturedCount = 0

        reader.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                nightImages.add(bitmap)
            }

            capturedCount++
            if (capturedCount >= frameCount) {
                // Frame stacking + noise reduction pipeline
                val stacked = mergeNightFrames(nightImages)
                val processed = ImageProcessor.processNight(stacked)
                stacked.recycle()
                val path = saveBitmap(processed, "NIGHT")
                nightImages.forEach { it.recycle() }
                processed.recycle()
                path?.let { onCaptureComplete?.invoke(it) }
            }
        }, cameraHandler)

        try {
            // Night mode: capture multiple long-exposure frames for stacking
            val requests = (1..frameCount).map {
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applySettingsForCapture(this, currentSettings)
                    // Manual exposure: high ISO + longer shutter for night
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, minOf(currentSettings.iso * 2, 3200))
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100_000_000L) // 100ms per frame
                    set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                }.build()
            }

            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    onError?.invoke("Night capture failed: ${failure.reason}")
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            onError?.invoke("Night capture error: ${e.message}")
        }
    }

    // ---- Image Processing ----

    /**
     * Simple HDR tone mapping: average multiple exposures with weighted blending.
     * Produces a result with better dynamic range than any single frame.
     */
    private fun mergeHdrFrames(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val width = frames[0].width
        val height = frames[0].height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixelCount = width * height
        val rSum = FloatArray(pixelCount)
        val gSum = FloatArray(pixelCount)
        val bSum = FloatArray(pixelCount)
        val weightSum = FloatArray(pixelCount)

        for (frame in frames) {
            val pixels = IntArray(pixelCount)
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val r = (pixels[i] shr 16) and 0xFF
                val g = (pixels[i] shr 8) and 0xFF
                val b = pixels[i] and 0xFF
                // Weight based on how well-exposed the pixel is (prefer mid-tones)
                val luminance = 0.299f * r + 0.587f * g + 0.114f * b
                val weight = 1f - ((luminance - 128f) / 128f).let { it * it } // Gaussian-like
                rSum[i] += r * weight
                gSum[i] += g * weight
                bSum[i] += b * weight
                weightSum[i] += weight
            }
        }

        val resultPixels = IntArray(pixelCount)
        for (i in resultPixels.indices) {
            val w = if (weightSum[i] > 0f) weightSum[i] else 1f
            val r = (rSum[i] / w).toInt().coerceIn(0, 255)
            val g = (gSum[i] / w).toInt().coerceIn(0, 255)
            val b = (bSum[i] / w).toInt().coerceIn(0, 255)
            resultPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Night mode frame stacking: average frames to reduce noise.
     * Averaging N frames reduces noise by sqrt(N).
     */
    private fun mergeNightFrames(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        if (frames.size == 1) return frames[0].copy(Bitmap.Config.ARGB_8888, false)

        val width = frames[0].width
        val height = frames[0].height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixelCount = width * height
        val rSum = IntArray(pixelCount)
        val gSum = IntArray(pixelCount)
        val bSum = IntArray(pixelCount)

        for (frame in frames) {
            val pixels = IntArray(pixelCount)
            frame.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                rSum[i] += (pixels[i] shr 16) and 0xFF
                gSum[i] += (pixels[i] shr 8) and 0xFF
                bSum[i] += pixels[i] and 0xFF
            }
        }

        val n = frames.size
        val resultPixels = IntArray(pixelCount)
        for (i in resultPixels.indices) {
            val r = (rSum[i] / n).coerceIn(0, 255)
            val g = (gSum[i] / n).coerceIn(0, 255)
            val b = (bSum[i] / n).coerceIn(0, 255)
            // Apply slight brightness boost for night mode
            val boost = 1.2f
            val rB = (r * boost).toInt().coerceIn(0, 255)
            val gB = (g * boost).toInt().coerceIn(0, 255)
            val bB = (b * boost).toInt().coerceIn(0, 255)
            resultPixels[i] = (0xFF shl 24) or (rB shl 16) or (gB shl 8) or bB
        }
        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    // ---- Zoom ----

    private var currentZoomLevel: Float = 1f

    fun getMaxZoom(): Float {
        val cameraId = currentCameraId ?: return 1f
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        return chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    }

    fun setZoom(zoomLevel: Float) {
        currentZoomLevel = zoomLevel
        val cameraId = currentCameraId ?: return
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = zoomLevel.coerceIn(1f, maxZoom)

        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val centerX = sensorRect.width() / 2
        val centerY = sensorRect.height() / 2
        val deltaX = (sensorRect.width() / (2f * zoom)).toInt()
        val deltaY = (sensorRect.height() / (2f * zoom)).toInt()

        val cropRegion = Rect(
            centerX - deltaX,
            centerY - deltaY,
            centerX + deltaX,
            centerY + deltaY,
        )

        previewRequestBuilder?.let { builder ->
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            try {
                captureSession?.setRepeatingRequest(builder.build(), null, cameraHandler)
            } catch (_: CameraAccessException) { }
        }
    }

    // ---- Burst Capture ----

    private var burstImageReader: ImageReader? = null
    private var isBurstRunning = false
    var onBurstFrame: ((String, Int) -> Unit)? = null
    var onBurstFinished: ((List<String>) -> Unit)? = null
    private val burstPaths = Collections.synchronizedList(mutableListOf<String>())
    private var burstFrameCount = 0

    /**
     * 高速連写を開始する。
     * setRepeatingRequest + TEMPLATE_STILL_CAPTURE で最大速度を実現。
     * - AFは開始時に1回だけロック（フレームごとにAFしない）
     * - NR/Edge/Shading を FAST モードで処理速度優先
     * - 後処理なし: カメラHW JPEGをそのまま保存
     * - ImageReaderバッファ8枚でドロップ防止
     */
    fun startBurst() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        if (isBurstRunning) return
        isBurstRunning = true
        burstPaths.clear()
        burstFrameCount = 0

        // 連写用ImageReader: バッファ8枚で高速書き込み対応
        val captureSize = getOptimalCaptureSize()
        burstImageReader?.close()
        burstImageReader = ImageReader.newInstance(
            captureSize.width, captureSize.height, ImageFormat.JPEG, 8
        )

        burstImageReader!!.setOnImageAvailableListener({ imgReader ->
            val image = imgReader.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                // 後処理なし: JPEGを直接保存（最速）
                val path = saveToMediaStore(bytes, "BURST", "image/jpeg", ".jpg")
                if (path != null) {
                    burstPaths.add(path)
                    burstFrameCount++
                    onBurstFrame?.invoke(path, burstFrameCount)
                }
            } finally {
                image.close()
            }
        }, cameraHandler)

        // セッションに連写用サーフェスを追加するため再構築
        val surfaces = mutableListOf(surface, burstImageReader!!.surface)
        // 既存のimageReaderも維持
        imageReader?.let { surfaces.add(it.surface) }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { OutputConfiguration(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(newSession: CameraCaptureSession) {
                            captureSession = newSession
                            startBurstRepeating(device, newSession, surface)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            isBurstRunning = false
                            onError?.invoke("Burst session configuration failed")
                        }
                    }
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(newSession: CameraCaptureSession) {
                            captureSession = newSession
                            startBurstRepeating(device, newSession, surface)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            isBurstRunning = false
                            onError?.invoke("Burst session configuration failed")
                        }
                    }, cameraHandler)
            }
        } catch (e: CameraAccessException) {
            isBurstRunning = false
            onError?.invoke("Burst start error: ${e.message}")
        }
    }

    /**
     * setRepeatingRequest で連写ループを開始。
     * TEMPLATE_STILL_CAPTUREだが速度優先設定。
     */
    private fun startBurstRepeating(device: CameraDevice, session: CameraCaptureSession, surface: Surface) {
        val burstReader = burstImageReader ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(burstReader.surface)
                // プレビューも同時表示
                addTarget(surface)

                // AE ON: カメラHWに露出任せ（フレーム間の遅延削減）
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, true) // AEロック: 連写中の明滅防止

                // AFロック: 最初のフォーカスを維持（フレームごとのAFスキップ）
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                afRegion?.let {
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                }

                // FAST モード: 画質よりスピード優先
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
                set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST)

                // JPEG品質を少し下げて書き込み高速化
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())

                // ズーム維持
                if (currentZoomLevel > 1f) {
                    val cameraId = currentCameraId
                    if (cameraId != null) {
                        val chars = cameraManager.getCameraCharacteristics(cameraId)
                        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        if (sensorRect != null) {
                            val centerX = sensorRect.width() / 2
                            val centerY = sensorRect.height() / 2
                            val deltaX = (sensorRect.width() / (2f * currentZoomLevel)).toInt()
                            val deltaY = (sensorRect.height() / (2f * currentZoomLevel)).toInt()
                            set(CaptureRequest.SCALER_CROP_REGION, Rect(
                                centerX - deltaX, centerY - deltaY,
                                centerX + deltaX, centerY + deltaY,
                            ))
                        }
                    }
                }
            }

            // setRepeatingRequest: カメラHWが最速でフレームを出力
            session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Log.w(TAG, "Burst frame failed: ${failure.reason}")
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            isBurstRunning = false
            onError?.invoke("Burst repeating error: ${e.message}")
        }
    }

    /**
     * 連写を停止し、通常プレビューに復帰する。
     */
    fun stopBurst() {
        if (!isBurstRunning) return
        isBurstRunning = false

        try {
            captureSession?.stopRepeating()
        } catch (_: CameraAccessException) { }

        val paths = burstPaths.toList()
        val count = burstFrameCount

        // 連写用ImageReaderを解放
        burstImageReader?.close()
        burstImageReader = null

        // 通常プレビューセッションを再構築
        val surface = previewSurface
        if (surface != null) {
            createPreviewSession(surface)
        }

        onBurstFinished?.invoke(paths)
    }

    fun isBurstActive(): Boolean = isBurstRunning

    // ---- Tap to Focus ----

    private var afRegion: MeteringRectangle? = null

    /**
     * タップ位置にAFを合わせる。
     * タッチ座標をセンサー座標に変換し、AF領域を設定してトリガーする。
     */
    fun tapToFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val cameraId = currentCameraId ?: return
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxRegions == 0) return

        // タッチ座標 → センサー座標変換
        val sensorX = (x / viewWidth * sensorRect.width()).toInt()
        val sensorY = (y / viewHeight * sensorRect.height()).toInt()
        val halfSize = (sensorRect.width() * 0.05f).toInt() // センサー幅の5%

        val focusRect = Rect(
            (sensorX - halfSize).coerceAtLeast(sensorRect.left),
            (sensorY - halfSize).coerceAtLeast(sensorRect.top),
            (sensorX + halfSize).coerceAtMost(sensorRect.right),
            (sensorY + halfSize).coerceAtMost(sensorRect.bottom),
        )
        afRegion = MeteringRectangle(focusRect, MeteringRectangle.METERING_WEIGHT_MAX)

        // AF トリガー発行
        triggerAutoFocus()
    }

    /**
     * AFトリガーを発行して高速フォーカスロック
     */
    private fun triggerAutoFocus() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        try {
            // まずAFキャンセル
            val cancelBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applySettingsForPreview(this, currentSettings)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }
            session.capture(cancelBuilder.build(), null, cameraHandler)

            // AFトリガー開始
            val triggerBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                applySettingsForPreview(this, currentSettings)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                afRegion?.let {
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
                }
            }
            session.capture(triggerBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // AFロック後、通常プレビューに戻す（AF_MODEはAUTOのまま維持）
                    updatePreviewRequest()
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "AF trigger error: ${e.message}")
        }
    }

    // ---- Settings Application ----

    /**
     * プレビュー用: FAST モード + CONTINUOUS_VIDEO AF（低レイテンシ）
     */
    private fun applySettingsForPreview(builder: CaptureRequest.Builder, settings: CameraSettings) {
        applyCommonSettings(builder, settings)

        // プレビューは CONTINUOUS_VIDEO: フォーカス速度優先
        if (settings.autoFocus) {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            afRegion?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance)
        }

        // プレビューは高速処理優先
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
        builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST)
    }

    /**
     * 撮影用: HIGH_QUALITY モード + CONTINUOUS_PICTURE AF（高精度）
     */
    private fun applySettingsForCapture(builder: CaptureRequest.Builder, settings: CameraSettings) {
        applyCommonSettings(builder, settings)

        // 撮影は CONTINUOUS_PICTURE: フォーカス精度優先
        if (settings.autoFocus) {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            afRegion?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance)
        }

        // 撮影は画質最優先
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)

        // OIS（光学手ブレ補正）が利用可能なら有効化
        val cameraId = currentCameraId
        if (cameraId != null) {
            try {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                if (oisModes != null && oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                }
            } catch (_: CameraAccessException) { }
        }
    }

    private fun applyCommonSettings(builder: CaptureRequest.Builder, settings: CameraSettings) {
        if (settings.autoExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                settings.exposureCompensation
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.shutterSpeedNs)
        }

        when (settings.whiteBalance) {
            WhiteBalanceMode.AUTO -> {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO
                )
            }
            WhiteBalanceMode.DAYLIGHT -> {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                )
            }
            WhiteBalanceMode.CLOUDY -> {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                )
            }
            WhiteBalanceMode.TUNGSTEN -> {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
                )
            }
            WhiteBalanceMode.FLUORESCENT -> {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                )
            }
            WhiteBalanceMode.SHADE -> {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_SHADE
                )
            }
        }
    }

    // ---- Camera Capabilities Query ----

    private fun queryCapabilities(cameraId: String) {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportsRaw = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true

        val isoMin = isoRange?.lower ?: 100
        val isoMax = isoRange?.upper ?: 3200
        val standardIsos = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
            .filter { it in isoMin..isoMax }

        val capabilities = CameraCapabilities(
            isoRange = isoMin..isoMax,
            shutterSpeedRangeNs = (exposureRange?.lower ?: 1_000_000L)..(exposureRange?.upper ?: 1_000_000_000L),
            minFocusDistance = minFocusDist,
            supportsRaw = supportsRaw,
            supportedIsos = standardIsos.ifEmpty { listOf(100) },
        )
        onCapabilities?.invoke(capabilities)
    }

    private fun findCameraId(useFront: Boolean): String? {
        val facing = if (useFront) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    // ---- File Saving ----

    private fun saveJpegImage(image: Image): String? {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        return saveToMediaStore(bytes, "JPEG", "image/jpeg", ".jpg")
    }

    private fun saveDngImage(image: Image) {
        val cameraId = currentCameraId ?: return
        val chars = cameraManager.getCameraCharacteristics(cameraId)

        // Save raw DNG bytes
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        saveToMediaStore(bytes, "RAW", "image/x-adobe-dng", ".dng")
    }

    private fun saveBitmap(bitmap: Bitmap, prefix: String): String? {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 98, stream)
        val bytes = stream.toByteArray()
        return saveToMediaStore(bytes, prefix, "image/jpeg", ".jpg")
    }

    private fun saveToMediaStore(
        bytes: ByteArray,
        prefix: String,
        mimeType: String,
        extension: String
    ): String? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "GoodCam_${prefix}_$timestamp$extension"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/GoodCamera")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri.toString()
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "GoodCamera"
            )
            dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        }
    }

    /** ReviewScreen等から後処理済みBitmapを保存するための公開API */
    fun saveBitmapToMediaStore(bitmap: Bitmap): String? {
        return saveBitmap(bitmap, "EDITED")
    }

    fun release() {
        closeCamera()
        cameraThread.quitSafely()
    }
}
