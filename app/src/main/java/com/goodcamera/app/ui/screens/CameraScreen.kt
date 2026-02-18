package com.goodcamera.app.ui.screens

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import com.goodcamera.app.camera.AppScreen
import com.goodcamera.app.camera.CaptureMode
import com.goodcamera.app.camera.CameraController
import com.goodcamera.app.camera.GridType
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

    // ライフサイクル管理: onPauseでカメラ解放、onResumeで再接続
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    viewModel.pauseCamera()
                }
                Lifecycle.Event.ON_RESUME -> {
                    textureView?.let { tv ->
                        val st = tv.surfaceTexture ?: return@let
                        if (viewModel.cameraController == null) {
                            val controller = CameraController(context)
                            viewModel.initController(controller)
                        }
                        val surface = android.view.Surface(st)
                        viewModel.resumeCamera(surface)
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ReviewScreen表示
    if (uiState.showReviewScreen && uiState.reviewImagePath != null) {
        ReviewScreen(
            imagePath = uiState.reviewImagePath!!,
            onBack = { viewModel.closeReviewScreen() },
            onSave = { bitmap -> viewModel.saveProcessedImage(bitmap) },
            autoContrastEnabled = uiState.autoContrastEnabled,
            aiProcessingConfig = if (uiState.captureMode == CaptureMode.AI_AUTO)
                viewModel.lastSceneRecommendation?.processingConfig else null,
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
                            viewModel.previewTextureView = this@apply
                            // コントローラーが未初期化なら作成（LaunchedEffectとのレース回避）
                            if (viewModel.cameraController == null) {
                                val controller = CameraController(ctx)
                                viewModel.initController(controller)
                            }
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
                            viewModel.pauseCamera()
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

        // ピンチズーム検出
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(uiState.maxZoom) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val newZoom = uiState.zoomLevel * zoom
                        viewModel.setZoomLevel(newZoom)
                    }
                },
        )

        // グリッドオーバーレイ
        GridOverlay(gridType = uiState.gridType)

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

        // タイマーカウントダウン表示
        if (uiState.timerCountdown > 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = uiState.timerCountdown.toString(),
                    color = Color.White,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 連写モード: 枚数カウンター
        AnimatedVisibility(
            visible = uiState.isBurstActive && uiState.burstCount > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = Color.Red.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 点滅する録画インジケーター
                    val dotAlpha = remember { Animatable(1f) }
                    LaunchedEffect(uiState.isBurstActive) {
                        if (uiState.isBurstActive) {
                            while (true) {
                                dotAlpha.animateTo(0.3f, tween(400))
                                dotAlpha.animateTo(1f, tween(400))
                            }
                        }
                    }
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(
                            color = Color.White.copy(alpha = dotAlpha.value),
                            radius = size.minDimension / 2f,
                        )
                    }
                    Text(
                        text = "${uiState.burstCount} 枚",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // AIモード: シーン検出バッジ
        AnimatedVisibility(
            visible = uiState.captureMode == CaptureMode.AI_AUTO && uiState.aiDetectedScene.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "AI",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = uiState.aiDetectedScene,
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    if (uiState.aiConfidence >= 0.7f) {
                        Text(
                            text = "${(uiState.aiConfidence * 100).toInt()}%",
                            color = Color.Black.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }

        // 上部: コントロールバー
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatSelector(
                    currentFormat = uiState.outputFormat,
                    supportsRaw = uiState.capabilities.supportsRaw,
                    onFormatSelected = { viewModel.setOutputFormat(it) },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // タイマーボタン
                IconButton(onClick = {
                    val next = when (uiState.timerSeconds) {
                        0 -> 3
                        3 -> 5
                        5 -> 10
                        else -> 0
                    }
                    viewModel.setTimerSeconds(next)
                }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = "タイマー",
                            tint = if (uiState.timerSeconds > 0)
                                MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp),
                        )
                        if (uiState.timerSeconds > 0) {
                            Text(
                                "${uiState.timerSeconds}s",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 9.sp,
                            )
                        }
                    }
                }

                // グリッドボタン
                IconButton(onClick = {
                    val next = when (uiState.gridType) {
                        GridType.NONE -> GridType.RULE_OF_THIRDS
                        GridType.RULE_OF_THIRDS -> GridType.GOLDEN_RATIO
                        GridType.GOLDEN_RATIO -> GridType.CROSSHAIR
                        GridType.CROSSHAIR -> GridType.NONE
                    }
                    viewModel.setGridType(next)
                }) {
                    Icon(
                        Icons.Filled.GridOn,
                        contentDescription = "グリッド",
                        tint = if (uiState.gridType != GridType.NONE)
                            MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = 0.4f),
                    )
                }

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

                // 設定ボタン
                IconButton(onClick = { viewModel.navigateTo(AppScreen.SETTINGS) }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "設定",
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // ズームスライダー（右側）
        if (uiState.maxZoom > 1f) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .height(200.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = String.format("%.1fx", uiState.zoomLevel),
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Slider(
                    value = uiState.zoomLevel,
                    onValueChange = { viewModel.setZoomLevel(it) },
                    valueRange = 1f..uiState.maxZoom,
                    modifier = Modifier
                        .weight(1f)
                        .width(48.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    ),
                )
            }
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

            // シャッターボタン & ギャラリー & カメラ切替
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ギャラリーボタン
                IconButton(
                    onClick = { viewModel.navigateTo(AppScreen.GALLERY) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Collections,
                        contentDescription = "ギャラリー",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                // シャッターボタン
                CaptureControls(
                    isCaptureInProgress = uiState.isCaptureInProgress || uiState.timerCountdown > 0,
                    onCapture = {
                        if (uiState.timerCountdown > 0) {
                            viewModel.cancelTimer()
                        } else {
                            viewModel.capturePhoto()
                        }
                    },
                    onSwitchCamera = {
                        textureView?.let { tv ->
                            val st = tv.surfaceTexture ?: return@let
                            val surface = android.view.Surface(st)
                            viewModel.switchCamera(surface)
                        }
                    },
                    isBurstMode = uiState.captureMode == CaptureMode.BURST,
                    isBurstActive = uiState.isBurstActive,
                    burstCount = uiState.burstCount,
                    onBurstStart = { viewModel.startBurst() },
                    onBurstStop = { viewModel.stopBurst() },
                )
            }
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
