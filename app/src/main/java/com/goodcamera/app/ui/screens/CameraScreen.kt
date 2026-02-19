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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.goodcamera.app.camera.AppScreen
import com.goodcamera.app.camera.GridType
import com.goodcamera.app.ui.components.GridOverlay
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

    LaunchedEffect(focusTapPosition) {
        if (focusTapPosition != null) {
            focusRingAlpha.snapTo(1f)
            focusRingScale.snapTo(1.5f)
            // 縮小アニメーション
            focusRingScale.animateTo(1f, animationSpec = tween(200))
            // フォーカス完了を待ってフェードアウト
            kotlinx.coroutines.delay(600)
            focusRingAlpha.animateTo(0f, animationSpec = tween(300))
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
                    color = Color.White,
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

        // 下部コントロール
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
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
