package com.goodcamera.app.ui.screens

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.goodcamera.app.camera.CameraController
import com.goodcamera.app.camera.CaptureMode
import com.goodcamera.app.ui.components.*
import com.goodcamera.app.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.launch

/**
 * メインのカメラ撮影画面
 * Camera2 APIのプレビュー表示、撮影モード切替、Pro手動制御を統合
 * 撮影後は自動的にReviewScreenへ遷移し、後処理の調整・比較が可能
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // TextureViewの参照を保持
    var textureView by remember { mutableStateOf<TextureView?>(null) }

    // タップフォーカスのインジケーター状態
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val focusAlpha = remember { Animatable(0f) }
    val focusScale = remember { Animatable(1.5f) }
    val coroutineScope = rememberCoroutineScope()

    // CameraControllerの初期化
    LaunchedEffect(Unit) {
        if (viewModel.cameraController == null) {
            val controller = CameraController(context)
            viewModel.initController(controller)
        }
    }

    // ReviewScreen表示
    if (uiState.showReviewScreen && uiState.reviewImagePath != null) {
        ReviewScreen(
            imagePath = uiState.reviewImagePath!!,
            onBack = { viewModel.closeReviewScreen() },
            onSave = { bitmap -> viewModel.saveProcessedImage(bitmap) },
            autoContrastEnabled = uiState.autoContrastEnabled,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // カメラプレビュー（TextureView）
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            textureView = this@apply
                            val previewSurface = android.view.Surface(surface)
                            viewModel.openCamera(
                                useFront = uiState.usingFrontCamera,
                                surface = previewSurface,
                            )
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {}

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            viewModel.cameraController?.closeCamera()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }

                    // タップフォーカス
                    setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            viewModel.tapToFocus(event.x, event.y, v.width, v.height)
                            focusPoint = Offset(event.x, event.y)
                            coroutineScope.launch {
                                focusScale.snapTo(1.5f)
                                focusAlpha.snapTo(1f)
                                focusScale.animateTo(1f, tween(200))
                                kotlinx.coroutines.delay(600)
                                focusAlpha.animateTo(0f, tween(300))
                            }
                            v.performClick()
                            true
                        } else false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // フォーカスインジケーター
        focusPoint?.let { point ->
            if (focusAlpha.value > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = focusAlpha.value),
                        radius = 40f * focusScale.value,
                        center = point,
                        style = Stroke(width = 2f),
                    )
                }
            }
        }

        // 上部: 出力形式セレクター
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatSelector(
                currentFormat = uiState.outputFormat,
                supportsRaw = uiState.capabilities.supportsRaw,
                onFormatSelected = { viewModel.setOutputFormat(it) },
            )

            // 自動コントラストトグル
            IconButton(onClick = { viewModel.setAutoContrast(!uiState.autoContrastEnabled) }) {
                Icon(
                    Icons.Filled.Contrast,
                    contentDescription = "自動コントラスト",
                    tint = if (uiState.autoContrastEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        Color.White.copy(alpha = 0.4f),
                )
            }

            // 撮影モード表示
            Text(
                text = uiState.captureMode.label,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }

        // 下部コントロール群
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Proモード制御パネル（Proモードの時のみ表示）
            if (uiState.captureMode == CaptureMode.PRO) {
                ProControlsPanel(
                    settings = uiState.settings,
                    capabilities = uiState.capabilities,
                    onIsoChanged = { viewModel.setIso(it) },
                    onShutterSpeedChanged = { viewModel.setShutterSpeed(it) },
                    onWhiteBalanceChanged = { viewModel.setWhiteBalance(it) },
                    onFocusDistanceChanged = { viewModel.setFocusDistance(it) },
                    onAutoExposureChanged = { viewModel.setAutoExposure(it) },
                    onAutoFocusChanged = { viewModel.setAutoFocus(it) },
                    onExposureCompChanged = { viewModel.setExposureCompensation(it) },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // 撮影モード選択バー
            ModeSelectorBar(
                currentMode = uiState.captureMode,
                onModeSelected = { viewModel.setCaptureMode(it) },
            )

            Spacer(modifier = Modifier.height(20.dp))

            // シャッターボタン & カメラ切替
            CaptureControls(
                isCaptureInProgress = uiState.isCaptureInProgress,
                onCapture = { viewModel.capturePhoto() },
                onSwitchCamera = {
                    textureView?.let { tv ->
                        val st = tv.surfaceTexture ?: return@let
                        val surface = android.view.Surface(st)
                        viewModel.switchCamera(surface)
                    }
                },
            )
        }

        // エラーメッセージ表示
        uiState.errorMessage?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("OK")
                    }
                },
            ) {
                Text(error)
            }
        }
    }
}
