package com.goodcamera.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.goodcamera.app.processing.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 撮影後レビュー画面
 * 後処理の適用・比較・保存ができる
 */
@Composable
fun ReviewScreen(
    imagePath: String,
    onBack: () -> Unit,
    onSave: (Bitmap) -> Unit,
    autoContrastEnabled: Boolean = true,
    aiProcessingConfig: ImageProcessor.ProcessingConfig? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 元画像とprocessed画像
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // AIモードの場合、AIが推奨した設定で初期化
    val aiConfig = aiProcessingConfig
    var autoWbEnabled by remember { mutableStateOf(aiConfig?.autoWbEnabled ?: true) }
    var denoiseEnabled by remember { mutableStateOf(aiConfig?.denoiseEnabled ?: true) }
    var denoiseStrength by remember { mutableStateOf(aiConfig?.denoiseStrength ?: ImageProcessor.DenoiseStrength.MEDIUM) }
    var deblurEnabled by remember { mutableStateOf(aiConfig?.deblurEnabled ?: true) }
    var perceptualEnabled by remember { mutableStateOf(aiConfig?.perceptualEnabled ?: true) }
    var sharpenEnabled by remember { mutableStateOf(aiConfig?.sharpenEnabled ?: true) }
    var sharpenAmount by remember { mutableFloatStateOf(aiConfig?.sharpenAmount ?: 1.2f) }
    var autoLevelsEnabled by remember { mutableStateOf(aiConfig?.autoLevelsEnabled ?: autoContrastEnabled) }
    var dehazeEnabled by remember { mutableStateOf(aiConfig?.dehazeEnabled ?: false) }
    var dehazeStrength by remember { mutableFloatStateOf(aiConfig?.dehazeStrength ?: 0.7f) }

    // 画像をロード
    LaunchedEffect(imagePath) {
        withContext(Dispatchers.IO) {
            val bitmap = if (imagePath.startsWith("content://")) {
                val uri = Uri.parse(imagePath)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                BitmapFactory.decodeFile(imagePath)
            }
            originalBitmap = bitmap
        }
    }

    // 後処理を適用
    LaunchedEffect(originalBitmap, autoWbEnabled, denoiseEnabled, denoiseStrength, deblurEnabled, perceptualEnabled, sharpenEnabled, sharpenAmount, autoLevelsEnabled, dehazeEnabled, dehazeStrength) {
        val original = originalBitmap ?: return@LaunchedEffect
        isProcessing = true
        withContext(Dispatchers.Default) {
            val config = ImageProcessor.ProcessingConfig(
                autoWbEnabled = autoWbEnabled,
                denoiseEnabled = denoiseEnabled,
                denoiseStrength = denoiseStrength,
                deblurEnabled = deblurEnabled,
                perceptualEnabled = perceptualEnabled,
                sharpenEnabled = sharpenEnabled,
                sharpenAmount = sharpenAmount,
                autoLevelsEnabled = autoLevelsEnabled,
                dehazeEnabled = dehazeEnabled,
                dehazeStrength = dehazeStrength,
            )
            processedBitmap = ImageProcessor.process(original, config)
        }
        isProcessing = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // 画像表示
        val displayBitmap = if (showOriginal) originalBitmap else processedBitmap ?: originalBitmap
        displayBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "撮影画像",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 処理中インジケータ
        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 上部バー
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "戻る", tint = Color.White)
            }

            Text(
                text = if (showOriginal) "元画像" else "処理済み",
                color = Color.White,
                fontSize = 14.sp,
            )

            Row {
                // 共有ボタン
                IconButton(onClick = {
                    val bitmap = processedBitmap ?: originalBitmap ?: return@IconButton
                    val cacheDir = File(context.cacheDir, "shared_images")
                    cacheDir.mkdirs()
                    val file = File(cacheDir, "GoodCam_share.jpg")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "共有"))
                }) {
                    Icon(Icons.Filled.Share, "共有", tint = Color.White)
                }

                // 保存ボタン
                IconButton(onClick = {
                    processedBitmap?.let { onSave(it) }
                }) {
                    Icon(Icons.Filled.Check, "保存", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 下部コントロール
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 設定パネル
            if (showSettings) {
                ProcessingSettingsPanel(
                    autoWbEnabled = autoWbEnabled,
                    denoiseEnabled = denoiseEnabled,
                    denoiseStrength = denoiseStrength,
                    deblurEnabled = deblurEnabled,
                    perceptualEnabled = perceptualEnabled,
                    sharpenEnabled = sharpenEnabled,
                    sharpenAmount = sharpenAmount,
                    autoLevelsEnabled = autoLevelsEnabled,
                    dehazeEnabled = dehazeEnabled,
                    dehazeStrength = dehazeStrength,
                    onAutoWbEnabledChanged = { autoWbEnabled = it },
                    onDenoiseEnabledChanged = { denoiseEnabled = it },
                    onDenoiseStrengthChanged = { denoiseStrength = it },
                    onDeblurEnabledChanged = { deblurEnabled = it },
                    onPerceptualEnabledChanged = { perceptualEnabled = it },
                    onSharpenEnabledChanged = { sharpenEnabled = it },
                    onSharpenAmountChanged = { sharpenAmount = it },
                    onAutoLevelsEnabledChanged = { autoLevelsEnabled = it },
                    onDehazeEnabledChanged = { dehazeEnabled = it },
                    onDehazeStrengthChanged = { dehazeStrength = it },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 比較ボタン（押している間だけ元画像表示）
                FilledTonalButton(
                    onClick = { showOriginal = !showOriginal },
                ) {
                    Icon(Icons.Filled.Compare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showOriginal) "処理後" else "比較")
                }

                // 設定ボタン
                FilledTonalButton(
                    onClick = { showSettings = !showSettings },
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("調整")
                }
            }
        }
    }
}

@Composable
private fun ProcessingSettingsPanel(
    autoWbEnabled: Boolean,
    denoiseEnabled: Boolean,
    denoiseStrength: ImageProcessor.DenoiseStrength,
    deblurEnabled: Boolean,
    perceptualEnabled: Boolean,
    sharpenEnabled: Boolean,
    sharpenAmount: Float,
    autoLevelsEnabled: Boolean,
    dehazeEnabled: Boolean,
    dehazeStrength: Float,
    onAutoWbEnabledChanged: (Boolean) -> Unit,
    onDenoiseEnabledChanged: (Boolean) -> Unit,
    onDenoiseStrengthChanged: (ImageProcessor.DenoiseStrength) -> Unit,
    onDeblurEnabledChanged: (Boolean) -> Unit,
    onPerceptualEnabledChanged: (Boolean) -> Unit,
    onSharpenEnabledChanged: (Boolean) -> Unit,
    onSharpenAmountChanged: (Float) -> Unit,
    onAutoLevelsEnabledChanged: (Boolean) -> Unit,
    onDehazeEnabledChanged: (Boolean) -> Unit,
    onDehazeStrengthChanged: (Float) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xDD1E1E1E)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("後処理設定", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))

            // 自動ホワイトバランス
            SettingRow(
                label = "自動ホワイトバランス",
                enabled = autoWbEnabled,
                onEnabledChanged = onAutoWbEnabledChanged,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ノイズリダクション
            SettingRow(
                label = "ノイズ除去",
                enabled = denoiseEnabled,
                onEnabledChanged = onDenoiseEnabledChanged,
            )
            if (denoiseEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ImageProcessor.DenoiseStrength.entries.forEach { strength ->
                        FilterChip(
                            selected = denoiseStrength == strength,
                            onClick = { onDenoiseStrengthChanged(strength) },
                            label = {
                                Text(
                                    when (strength) {
                                        ImageProcessor.DenoiseStrength.LIGHT -> "弱"
                                        ImageProcessor.DenoiseStrength.MEDIUM -> "中"
                                        ImageProcessor.DenoiseStrength.STRONG -> "強"
                                    },
                                    fontSize = 12.sp,
                                )
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ブレ/ピンぼけ補正
            SettingRow(
                label = "ブレ/ピンぼけ補正",
                enabled = deblurEnabled,
                onEnabledChanged = onDeblurEnabledChanged,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 知覚補正
            SettingRow(
                label = "知覚補正（目の見え方に近く）",
                enabled = perceptualEnabled,
                onEnabledChanged = onPerceptualEnabledChanged,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // シャープニング
            SettingRow(
                label = "シャープニング",
                enabled = sharpenEnabled,
                onEnabledChanged = onSharpenEnabledChanged,
            )
            if (sharpenEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("弱", color = Color.Gray, fontSize = 11.sp)
                    Slider(
                        value = sharpenAmount,
                        onValueChange = onSharpenAmountChanged,
                        valueRange = 0.3f..2.5f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Text("強", color = Color.Gray, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 自動レベル補正
            SettingRow(
                label = "自動コントラスト",
                enabled = autoLevelsEnabled,
                onEnabledChanged = onAutoLevelsEnabledChanged,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // かすみ除去
            SettingRow(
                label = "かすみ除去",
                enabled = dehazeEnabled,
                onEnabledChanged = onDehazeEnabledChanged,
            )
            if (dehazeEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("弱", color = Color.Gray, fontSize = 11.sp)
                    Slider(
                        value = dehazeStrength,
                        onValueChange = onDehazeStrengthChanged,
                        valueRange = 0.2f..1.0f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Text("強", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChanged,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
