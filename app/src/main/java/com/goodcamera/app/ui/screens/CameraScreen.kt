package com.goodcamera.app.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.goodcamera.app.ui.components.GridOverlay
import com.goodcamera.app.ui.components.ModeSelectorBar
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
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusTapPosition = offset
                        viewModel.tapToFocus(previewView, offset.x, offset.y)
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

        // グリッドオーバーレイ
        GridOverlay(gridType = uiState.gridType)

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

        // 右側: 縦の露出補正スライダー
        val evRange = uiState.capabilities.exposureCompensationRange
        if (evRange.first < evRange.last) {
            VerticalEvSlider(
                ev = uiState.settings.exposureCompensation,
                evRange = evRange,
                onEvChanged = { viewModel.setExposureCompensation(it) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
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
            // モード選択バー
            ModeSelectorBar(
                currentMode = uiState.captureMode,
                onModeSelected = { viewModel.setCaptureMode(it) },
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // フォーカススライダー (全モード共通)
            if (uiState.capabilities.minFocusDistance > 0f) {
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
        // Sliderを90度回転して縦にする
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = ev.toFloat(),
                onValueChange = { onEvChanged(it.toInt()) },
                valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
                steps = (evRange.last - evRange.first) - 1,
                modifier = Modifier
                    .width(200.dp)
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
