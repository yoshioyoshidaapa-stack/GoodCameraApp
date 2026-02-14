package com.goodcamera.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodcamera.app.camera.GridType
import com.goodcamera.app.processing.ImageProcessor

/**
 * 設定画面 - アプリ全体の設定を管理
 */
@Composable
fun SettingsScreen(
    gridType: GridType,
    timerSeconds: Int,
    autoContrastEnabled: Boolean,
    onGridTypeChanged: (GridType) -> Unit,
    onTimerSecondsChanged: (Int) -> Unit,
    onAutoContrastChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column {
            // ヘッダー
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, "戻る", tint = Color.White)
                }
                Text(
                    text = "設定",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 撮影設定セクション
                SettingsSection(title = "撮影設定") {
                    // グリッド表示
                    SettingsItem(title = "グリッド表示") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GridType.entries.forEach { type ->
                                FilterChip(
                                    selected = gridType == type,
                                    onClick = { onGridTypeChanged(type) },
                                    label = { Text(type.label, fontSize = 12.sp) },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // セルフタイマー
                    SettingsItem(title = "セルフタイマー") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0, 3, 5, 10).forEach { seconds ->
                                FilterChip(
                                    selected = timerSeconds == seconds,
                                    onClick = { onTimerSecondsChanged(seconds) },
                                    label = {
                                        Text(
                                            if (seconds == 0) "OFF" else "${seconds}秒",
                                            fontSize = 12.sp,
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 自動コントラスト
                    SettingsToggle(
                        title = "自動コントラスト",
                        description = "撮影後に自動でコントラスト調整",
                        checked = autoContrastEnabled,
                        onCheckedChange = onAutoContrastChanged,
                    )
                }

                // アプリ情報セクション
                SettingsSection(title = "アプリ情報") {
                    SettingsInfo("アプリ名", "GoodCamera")
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingsInfo("バージョン", "1.0.0")
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingsInfo("対応機能", "AUTO / PRO / HDR / Night")
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingsInfo("画像処理", "WB / NR / Deblur / Sharpen / Perceptual")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(title, color = Color.White, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(description, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun SettingsInfo(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}
