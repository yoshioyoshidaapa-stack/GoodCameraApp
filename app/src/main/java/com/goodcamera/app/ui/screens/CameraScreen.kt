package com.goodcamera.app.ui.screens

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.goodcamera.app.camera.CameraController
import com.goodcamera.app.camera.CaptureMode
import com.goodcamera.app.ui.components.*
import com.goodcamera.app.ui.viewmodel.CameraViewModel

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
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

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
