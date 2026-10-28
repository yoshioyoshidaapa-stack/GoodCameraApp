package com.goodcamera.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
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
import com.goodcamera.app.util.BitmapLoader
import com.goodcamera.app.util.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ReviewScreen"

/** プレビュー用に読み込む画像の長辺の上限 (px)。調整のたびの再処理を軽くする */
private const val PREVIEW_MAX_SIZE = 2048

/** スライダー操作中に毎回処理し直さないための待ち時間 (ms) */
private const val PROCESS_DEBOUNCE_MS = 200L

/**
 * 撮影後レビュー画面
 * 後処理の適用・比較・保存ができる
 *
 * 画面上の確認は縮小画像で行い、保存するときだけ元の解像度で処理する。
 *
 * @param onSaved 保存が終わったときに呼ばれる (保存先 URI。失敗時は null)
 */
@Composable
fun ReviewScreen(
    imagePath: String,
    onBack: () -> Unit,
    onSaved: (String?) -> Unit,
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
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val imageUri = remember(imagePath) { toUri(imagePath) }

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

    fun currentConfig() = ImageProcessor.ProcessingConfig(
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

    // 画像をロード (プレビュー用に縮小、EXIF の向きを反映)
    LaunchedEffect(imageUri) {
        originalBitmap = withContext(Dispatchers.IO) {
            try {
                BitmapLoader.load(context, imageUri, PREVIEW_MAX_SIZE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load image", e)
                null
            }
        }
    }

    // 後処理を適用
    // 設定が変わると LaunchedEffect が作り直され、前の処理はキャンセルされる。
    // 処理の途中でも ensureActive() で止まるので、古い設定の結果で表示が上書きされない。
    val config = currentConfig()
    LaunchedEffect(originalBitmap, config) {
        val original = originalBitmap ?: return@LaunchedEffect
        delay(PROCESS_DEBOUNCE_MS)
        isProcessing = true
        val result = withContext(Dispatchers.Default) {
            try {
                ImageProcessor.process(original, config) { ensureActive() }
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "Out of memory while processing preview", oom)
                null
            }
        }
        processedBitmap = result
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
        if (isProcessing || isSaving) {
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

                // 保存ボタン (元の解像度で処理し直して保存する)
                IconButton(
                    enabled = !isSaving && originalBitmap != null,
                    onClick = {
                        isSaving = true
                        val saveConfig = currentConfig()
                        scope.launch {
                            val savedPath = withContext(Dispatchers.Default) {
                                saveFullResolution(context, imageUri, saveConfig)
                            }
                            isSaving = false
                            onSaved(savedPath)
                        }
                    },
                ) {
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

private fun toUri(imagePath: String): Uri =
    if (imagePath.startsWith("content://") || imagePath.startsWith("file://")) {
        Uri.parse(imagePath)
    } else {
        Uri.fromFile(File(imagePath))
    }

/**
 * 元の解像度で読み込み直して補正をかけ、新しい写真として保存する。
 * @return 保存先 URI。失敗時 (メモリ不足を含む) は null
 */
private fun saveFullResolution(
    context: Context,
    uri: Uri,
    config: ImageProcessor.ProcessingConfig,
): String? {
    return try {
        val original = BitmapLoader.load(context, uri) ?: return null
        val processed = try {
            ImageProcessor.process(original, config)
        } catch (t: Throwable) {
            original.recycle()
            throw t
        }
        // 補正をすべてOFFにした場合は original がそのまま返る
        if (processed !== original) original.recycle()
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            MediaStoreSaver.saveJpeg(context, processed, "GoodCam_Edit_$timestamp.jpg")
        } finally {
            processed.recycle()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to save processed image", t)
        null
    }
}
