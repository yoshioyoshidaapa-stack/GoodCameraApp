package com.goodcamera.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodcamera.app.camera.GridType

@Composable
fun SettingsScreen(
    gridType: GridType,
    onGridTypeChanged: (GridType) -> Unit,
    shutterSoundEnabled: Boolean,
    onShutterSoundChanged: (Boolean) -> Unit,
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
                    @Suppress("DEPRECATION")
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
                    Spacer(modifier = Modifier.height(12.dp))
                    // シャッター音
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("シャッター音", color = Color.White, fontSize = 14.sp)
                        Switch(
                            checked = shutterSoundEnabled,
                            onCheckedChange = onShutterSoundChanged,
                        )
                    }
                }

                // アプリ情報セクション
                SettingsSection(title = "アプリ情報") {
                    SettingsInfo("アプリ名", "GoodCamera")
                    Spacer(modifier = Modifier.height(4.dp))
                    SettingsInfo("バージョン", "1.0.0")
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
private fun SettingsInfo(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.Gray, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}
