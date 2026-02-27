package com.goodcamera.app.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.goodcamera.app.camera.AppScreen
import com.goodcamera.app.camera.CaptureMode
import com.goodcamera.app.camera.GridType
import com.goodcamera.app.camera.NormalizedFace
import com.goodcamera.app.ui.components.GridOverlay
import com.goodcamera.app.ui.components.ModeSelectorBar
import com.goodcamera.app.ui.components.ProControlsPanel
import com.goodcamera.app.ui.viewmodel.CameraViewModel

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    // タップフォーカス状態
    var focusTapPosition by remember { mutableStateOf<Offset?>(null) }
    val focusRingAlpha = remember { Animatable(0f) }
    val focusRingScale = remember { Animatable(1.5f) }
    var focusRingColor by remember { mutableStateOf(Color.White) }

    // フォーカスルーペ状態
    var loupePosition by remember { mutableStateOf<Offset?>(null) }
    var loupeImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val isManualFocus = !uiState.settings.autoFocus || uiState.isMacroActive

    // ルーペ表示中はプレビュービットマップを定期更新
    LaunchedEffect(loupePosition != null) {
        if (loupePosition != null) {
            while (true) {
                val bmp = previewView.bitmap
                if (bmp != null) {
                    loupeImageBitmap = bmp.asImageBitmap()
                }
                kotlinx.coroutines.delay(150)
            }
        }
        loupeImageBitmap = null
    }

    // フォーカスロック完了でリングの色を変える
    LaunchedEffect(uiState.focusLocked) {
        if (focusTapPosition != null) {
            focusRingColor = if (uiState.focusLocked) Color.Green else Color.Yellow
        }
    }

    LaunchedEffect(focusTapPosition) {
        if (focusTapPosition != null) {
            focusRingColor = Color.White
            focusRingAlpha.snapTo(1f)
            focusRingScale.snapTo(1.4f)
            // 高速縮小 (150ms)
            focusRingScale.animateTo(1f, animationSpec = tween(150))
            // フォーカスロック待ち → フェードアウト
            kotlinx.coroutines.delay(800)
            focusRingAlpha.animateTo(0f, animationSpec = tween(200))
        }
    }

    // CameraXはLifecycleOwnerにバインドするので自動でライフサイクル管理される
    LaunchedEffect(uiState.usingFrontCamera) {
        viewModel.startCamera(context, lifecycleOwner, previewView)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // カメラプレビュー
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isManualFocus) {
                    if (isManualFocus) {
                        // MF時: タップ → AF復帰+タップフォーカス、長押し → ルーペ表示
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown()
                                val downPos = down.position

                                // 250ms以内にリリース → タップ判定
                                var releasedBeforeTimeout = false
                                withTimeoutOrNull(250L) {
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.none { it.pressed }) {
                                            releasedBeforeTimeout = true
                                        }
                                    } while (!releasedBeforeTimeout)
                                }

                                if (releasedBeforeTimeout) {
                                    // タップ → AFに戻してフォーカス
                                    focusTapPosition = downPos
                                    viewModel.tapToFocus(previewView, downPos.x, downPos.y)
                                } else {
                                    // 長押し → ルーペ表示
                                    loupePosition = downPos

                                    var pressed = true
                                    while (pressed) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull()
                                        if (change != null && change.pressed) {
                                            loupePosition = change.position
                                            change.consume()
                                        } else {
                                            pressed = false
                                        }
                                    }
                                    loupePosition = null
                                }
                            }
                        }
                    } else {
                        // AF時: タップフォーカス
                        detectTapGestures { offset ->
                            focusTapPosition = offset
                            viewModel.tapToFocus(previewView, offset.x, offset.y)
                        }
                    }
                },
        )

        // フォーカスインジケーター
        val ringRadius = with(LocalDensity.current) { 32.dp.toPx() }
        focusTapPosition?.let { pos ->
            androidx.compose.foundation.Canvas(
                modifier = Modifier.fillMaxSize(),
            ) {
                drawCircle(
                    color = focusRingColor,
                    radius = ringRadius * focusRingScale.value,
                    center = pos,
                    alpha = focusRingAlpha.value,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        // フォーカスルーペ (マニュアルフォーカス時)
        val currentLoupeBitmap = loupeImageBitmap
        val currentLoupePos = loupePosition
        if (currentLoupeBitmap != null && currentLoupePos != null) {
            FocusLoupe(
                previewBitmap = currentLoupeBitmap,
                touchPosition = currentLoupePos,
            )
        }

        // 顔検出オーバーレイ
        if (uiState.faceDetectionActive && uiState.detectedFaces.isNotEmpty()) {
            FaceDetectionOverlay(
                faces = uiState.detectedFaces,
                isFocusLocked = uiState.faceFocusLocked,
            )
        }

        // グリッドオーバーレイ
        GridOverlay(gridType = uiState.gridType)

        // AIシーン検出バッジ
        if (uiState.captureMode == CaptureMode.AI_AUTO && uiState.aiDetectedScene.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (uiState.faceDetectionActive) Color(0xCCFF6F00)
                        else Color(0xCC1E88E5),
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "AI ${uiState.aiDetectedScene}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 上部コントロール
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

            // 設定ボタン
            IconButton(onClick = { viewModel.navigateTo(AppScreen.SETTINGS) }) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "設定",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        // 接写モードインジケーター
        if (uiState.isMacroActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC4CAF50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "\uD83C\uDF3C 接写",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // ナイトモード撮影中オーバーレイ
        if (uiState.captureMode == CaptureMode.NIGHT && uiState.isCaptureInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (uiState.nightProcessing) {
                        CircularProgressIndicator(color = Color(0xFF7C4DFF))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "処理中...",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Text(
                            text = "${uiState.nightCapturedFrames} / ${uiState.nightFrameCount}",
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = {
                                uiState.nightCapturedFrames.toFloat() / uiState.nightFrameCount
                            },
                            modifier = Modifier
                                .width(200.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF7C4DFF),
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "撮影中...端末を動かさないでください",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        // ナイトモードインジケーター
        if (uiState.captureMode == CaptureMode.NIGHT && !uiState.isCaptureInProgress) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC7C4DFF))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "Night",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 左側: 縦の露出補正スライダー (Proモードでは非表示 — ProControlsPanel内にEV制御あり)
        val evRange = uiState.capabilities.exposureCompensationRange
        if (evRange.first < evRange.last && uiState.captureMode != CaptureMode.PRO) {
            VerticalEvSlider(
                ev = uiState.settings.exposureCompensation,
                evRange = evRange,
                onEvChanged = { viewModel.setExposureCompensation(it) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 8.dp, top = 80.dp, bottom = 160.dp),
            )
        }

        // 下部コントロール
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Proモード制御パネル
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
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // モード選択バー
            ModeSelectorBar(
                currentMode = uiState.captureMode,
                onModeSelected = { viewModel.setCaptureMode(it) },
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // フォーカススライダー (Proモードでは非表示 — ProControlsPanel内にMF制御あり)
            if (uiState.capabilities.minFocusDistance > 0f && uiState.captureMode != CaptureMode.PRO) {
                val isMacro = uiState.isMacroActive
                FocusSlider(
                    focusDistance = if (isMacro) uiState.macroFocusDistance else uiState.settings.focusDistance,
                    maxDistance = uiState.capabilities.minFocusDistance,
                    onDistanceChanged = { d ->
                        if (isMacro) viewModel.setMacroFocusDistance(d)
                        else viewModel.setFocusDistance(d)
                    },
                    accentColor = if (isMacro) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
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
                ShutterButton(
                    isCapturing = uiState.isCaptureInProgress,
                    onClick = { viewModel.capturePhoto() },
                )

                // カメラ切替ボタン
                IconButton(
                    onClick = { viewModel.switchCamera(context, lifecycleOwner, previewView) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = "カメラ切替",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }

        // エラーメッセージ
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

@Composable
private fun ShutterButton(
    isCapturing: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isCapturing) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.4f),
        label = "shutterScale",
    )

    Box(
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .clickable(enabled = !isCapturing) { onClick() }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    if (isCapturing) MaterialTheme.colorScheme.primary
                    else Color.White,
                ),
        )
    }
}

@Composable
private fun FocusSlider(
    focusDistance: Float,
    maxDistance: Float,
    onDistanceChanged: (Float) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Filled.FilterCenterFocus,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "∞",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
        Slider(
            value = focusDistance,
            onValueChange = onDistanceChanged,
            valueRange = 0f..maxDistance,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
        Text(
            text = "近",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 2.dp),
        )
    }
}

@Composable
private fun VerticalEvSlider(
    ev: Int,
    evRange: IntRange,
    onEvChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "+",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        // Sliderを90度回転して縦にする — requiredWidthで親の制約を無視
        BoxWithConstraints(
            modifier = Modifier
                .width(48.dp)
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val sliderLength = this.maxHeight
            Slider(
                value = ev.toFloat(),
                onValueChange = { onEvChanged(it.toInt()) },
                valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
                steps = (evRange.last - evRange.first) - 1,
                modifier = Modifier
                    .requiredWidth(sliderLength)
                    .graphicsLayer {
                        rotationZ = -90f
                        transformOrigin = TransformOrigin.Center
                    },
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFC107),
                    activeTrackColor = Color(0xFFFFC107),
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
            )
        }
        Text(
            text = "-",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "${if (ev >= 0) "+" else ""}$ev",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * フォーカスルーペ: タッチ位置周辺を円形に拡大表示する。
 * 指の上方にオフセットして表示し、画面上端に近い場合は下方に切り替える。
 */
@Composable
private fun FocusLoupe(
    previewBitmap: ImageBitmap,
    touchPosition: Offset,
    modifier: Modifier = Modifier,
    magnification: Float = 3f,
) {
    val density = LocalDensity.current
    val loupeRadiusPx = with(density) { 100.dp.toPx() }
    val borderWidthPx = with(density) { 2.dp.toPx() }
    val crosshairPx = with(density) { 8.dp.toPx() }

    androidx.compose.foundation.Canvas(
        modifier = modifier.fillMaxSize(),
    ) {
        val scaleX = previewBitmap.width.toFloat() / size.width
        val scaleY = previewBitmap.height.toFloat() / size.height

        // ソース領域 (ビットマップ座標)
        val srcHalfW = (loupeRadiusPx * scaleX / magnification).toInt()
        val srcHalfH = (loupeRadiusPx * scaleY / magnification).toInt()
        val srcCenterX = (touchPosition.x * scaleX).toInt()
        val srcCenterY = (touchPosition.y * scaleY).toInt()

        // ルーペの表示位置 (指の上方、画面端ではクランプ or 下方にフリップ)
        val loupeCenterX = touchPosition.x.coerceIn(loupeRadiusPx, size.width - loupeRadiusPx)
        val aboveY = touchPosition.y - loupeRadiusPx * 2.5f
        val belowY = touchPosition.y + loupeRadiusPx * 2.5f
        val loupeCenterY = if (aboveY >= loupeRadiusPx + borderWidthPx) {
            aboveY
        } else {
            belowY.coerceAtMost(size.height - loupeRadiusPx - borderWidthPx)
        }

        // 円形クリップパス
        val circlePath = Path().apply {
            addOval(
                androidx.compose.ui.geometry.Rect(
                    left = loupeCenterX - loupeRadiusPx,
                    top = loupeCenterY - loupeRadiusPx,
                    right = loupeCenterX + loupeRadiusPx,
                    bottom = loupeCenterY + loupeRadiusPx,
                ),
            )
        }

        // ソース領域のクランプ
        val srcLeft = (srcCenterX - srcHalfW).coerceAtLeast(0)
        val srcTop = (srcCenterY - srcHalfH).coerceAtLeast(0)
        val srcW = (srcHalfW * 2).coerceAtMost(previewBitmap.width - srcLeft)
        val srcH = (srcHalfH * 2).coerceAtMost(previewBitmap.height - srcTop)

        if (srcW > 0 && srcH > 0) {
            // 半透明の背景円
            drawCircle(
                color = Color.Black.copy(alpha = 0.5f),
                radius = loupeRadiusPx + borderWidthPx,
                center = Offset(loupeCenterX, loupeCenterY),
            )

            // 拡大画像を円形にクリップして描画
            clipPath(circlePath) {
                drawImage(
                    image = previewBitmap,
                    srcOffset = IntOffset(srcLeft, srcTop),
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset(
                        (loupeCenterX - loupeRadiusPx).toInt(),
                        (loupeCenterY - loupeRadiusPx).toInt(),
                    ),
                    dstSize = IntSize(
                        (loupeRadiusPx * 2).toInt(),
                        (loupeRadiusPx * 2).toInt(),
                    ),
                )
            }

            // 白枠
            drawCircle(
                color = Color.White,
                radius = loupeRadiusPx,
                center = Offset(loupeCenterX, loupeCenterY),
                style = Stroke(width = borderWidthPx),
            )

            // 十字線
            drawLine(
                Color.White.copy(alpha = 0.6f),
                Offset(loupeCenterX - crosshairPx, loupeCenterY),
                Offset(loupeCenterX + crosshairPx, loupeCenterY),
                strokeWidth = 1f,
            )
            drawLine(
                Color.White.copy(alpha = 0.6f),
                Offset(loupeCenterX, loupeCenterY - crosshairPx),
                Offset(loupeCenterX, loupeCenterY + crosshairPx),
                strokeWidth = 1f,
            )
        }
    }
}

/**
 * 顔検出オーバーレイ: 検出された顔の位置に枠を描画する。
 */
@Composable
private fun FaceDetectionOverlay(
    faces: List<NormalizedFace>,
    isFocusLocked: Boolean,
) {
    val borderColor = if (isFocusLocked) Color(0xFFFF6F00) else Color.White.copy(alpha = 0.7f)
    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }
    val cornerLength = with(LocalDensity.current) { 12.dp.toPx() }

    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize(),
    ) {
        for (face in faces) {
            val cx = face.centerX * size.width
            val cy = face.centerY * size.height
            val fw = face.width * size.width * 1.3f  // 少し余白をつける
            val fh = face.height * size.height * 1.3f
            val left = cx - fw / 2f
            val top = cy - fh / 2f
            val right = cx + fw / 2f
            val bottom = cy + fh / 2f

            // 四隅のコーナーブラケットを描画
            // 左上
            drawLine(borderColor, Offset(left, top), Offset(left + cornerLength, top), strokeWidth)
            drawLine(borderColor, Offset(left, top), Offset(left, top + cornerLength), strokeWidth)
            // 右上
            drawLine(borderColor, Offset(right, top), Offset(right - cornerLength, top), strokeWidth)
            drawLine(borderColor, Offset(right, top), Offset(right, top + cornerLength), strokeWidth)
            // 左下
            drawLine(borderColor, Offset(left, bottom), Offset(left + cornerLength, bottom), strokeWidth)
            drawLine(borderColor, Offset(left, bottom), Offset(left, bottom - cornerLength), strokeWidth)
            // 右下
            drawLine(borderColor, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeWidth)
            drawLine(borderColor, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeWidth)
        }
    }
}
