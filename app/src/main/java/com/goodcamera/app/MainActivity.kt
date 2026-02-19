package com.goodcamera.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.goodcamera.app.camera.AppScreen
import com.goodcamera.app.ui.screens.CameraScreen
import com.goodcamera.app.ui.screens.GalleryScreen
import com.goodcamera.app.ui.screens.SettingsScreen
import com.goodcamera.app.ui.theme.GoodCameraTheme
import com.goodcamera.app.ui.viewmodel.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

class MainActivity : ComponentActivity() {

    private var isReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        splashScreen.setKeepOnScreenCondition { !isReady }

        // フォールバック: Composeに依存せず、Handlerで確実にスプラッシュを2秒後に解除
        Handler(Looper.getMainLooper()).postDelayed({ isReady = true }, 2000)

        setContent {
            GoodCameraTheme {
                CameraAppContent(
                    onPreviewReady = { isReady = true },
                )
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraAppContent(
    onPreviewReady: () -> Unit = {},
) {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        val viewModel: CameraViewModel = viewModel()
        val uiState by viewModel.uiState.collectAsState()

        // プレビューが開始されたらスプラッシュを消す
        if (uiState.isPreviewActive) {
            onPreviewReady()
        }

        when (uiState.currentScreen) {
            AppScreen.CAMERA -> {
                CameraScreen(viewModel = viewModel)
            }
            AppScreen.GALLERY -> {
                GalleryScreen(
                    onBack = { viewModel.navigateTo(AppScreen.CAMERA) },
                )
            }
            AppScreen.SETTINGS -> {
                SettingsScreen(
                    gridType = uiState.gridType,
                    timerSeconds = uiState.timerSeconds,
                    autoContrastEnabled = uiState.autoContrastEnabled,
                    onGridTypeChanged = { viewModel.setGridType(it) },
                    onTimerSecondsChanged = { viewModel.setTimerSeconds(it) },
                    onAutoContrastChanged = { viewModel.setAutoContrast(it) },
                    onBack = { viewModel.navigateTo(AppScreen.CAMERA) },
                )
            }
        }
    } else {
        // パーミッション未許可の場合はスプラッシュをすぐ消す
        onPreviewReady()
        PermissionRequestScreen(
            shouldShowRationale = cameraPermissionState.status.shouldShowRationale,
            onRequestPermission = { cameraPermissionState.launchPermissionRequest() },
        )
    }
}

@Composable
private fun PermissionRequestScreen(
    shouldShowRationale: Boolean,
    onRequestPermission: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "GoodCamera",
                color = Color.White,
                fontSize = 28.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (shouldShowRationale) {
                    "カメラアプリの動作にはカメラへのアクセス許可が必要です。\n設定からカメラの権限を有効にしてください。"
                } else {
                    "カメラへのアクセスを許可してください。\nタブレットのカメラ性能を最大限引き出します。"
                },
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("カメラを許可", color = Color.Black)
            }
        }
    }
}
