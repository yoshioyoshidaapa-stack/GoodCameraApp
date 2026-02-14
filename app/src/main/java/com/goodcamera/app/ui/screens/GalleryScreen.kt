package com.goodcamera.app.ui.screens

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GalleryItem(
    val uri: Uri,
    val id: Long,
    val name: String,
    val dateModified: Long,
)

/**
 * ギャラリー画面 - GoodCameraで撮影した写真を一覧表示
 */
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var selectedPhoto by remember { mutableStateOf<GalleryItem?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // 写真一覧を読み込む
    LaunchedEffect(Unit) {
        photos = withContext(Dispatchers.IO) {
            loadGoodCameraPhotos(context)
        }
        isLoading = false
    }

    // 写真の詳細表示
    if (selectedPhoto != null) {
        PhotoDetailScreen(
            item = selectedPhoto!!,
            onBack = { selectedPhoto = null },
            onDelete = { item ->
                context.contentResolver.delete(item.uri, null, null)
                photos = photos.filter { it.id != item.id }
                selectedPhoto = null
            },
            onShare = { item ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "共有"))
            },
        )
        return
    }

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
                    text = "ギャラリー",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${photos.size}枚",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (photos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "まだ写真がありません\nカメラで撮影してください",
                        color = Color.Gray,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(photos, key = { it.id }) { item ->
                        GalleryThumbnail(
                            item = item,
                            onClick = { selectedPhoto = item },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryThumbnail(
    item: GalleryItem,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var thumbnail by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(item.id) {
        thumbnail = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 4
                    }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        thumbnail?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = Color.Gray,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun PhotoDetailScreen(
    item: GalleryItem,
    onBack: () -> Unit,
    onDelete: (GalleryItem) -> Unit,
    onShare: (GalleryItem) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("写真を削除") },
            text = { Text("この写真を削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete(item)
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
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
            Row {
                IconButton(onClick = { onShare(item) }) {
                    Icon(Icons.Filled.Share, "共有", tint = Color.White)
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Filled.Delete, "削除", tint = Color.White)
                }
            }
        }
    }
}

private fun loadGoodCameraPhotos(context: Context): List<GalleryItem> {
    val photos = mutableListOf<GalleryItem>()
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_MODIFIED,
    )

    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    } else {
        "${MediaStore.Images.Media.DATA} LIKE ?"
    }

    val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf("${Environment.DIRECTORY_DCIM}/GoodCamera%")
    } else {
        arrayOf("%/DCIM/GoodCamera%")
    }

    val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

    context.contentResolver.query(
        collection, projection, selection, selectionArgs, sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn)
            val dateModified = cursor.getLong(dateColumn)
            val uri = ContentUris.withAppendedId(collection, id)

            photos.add(GalleryItem(uri, id, name, dateModified))
        }
    }

    return photos
}
