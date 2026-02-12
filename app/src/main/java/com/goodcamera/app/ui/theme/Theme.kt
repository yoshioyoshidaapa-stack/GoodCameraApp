package com.goodcamera.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CameraDarkScheme = darkColorScheme(
    primary = Color(0xFFFFC107),
    onPrimary = Color.Black,
    secondary = Color(0xFF80CBC4),
    onSecondary = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFBBBBBB),
    error = Color(0xFFCF6679),
)

@Composable
fun GoodCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CameraDarkScheme,
        content = content,
    )
}
