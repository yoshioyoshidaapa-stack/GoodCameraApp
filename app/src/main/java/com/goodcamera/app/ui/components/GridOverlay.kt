package com.goodcamera.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.goodcamera.app.camera.GridType

/**
 * カメラプレビュー上にグリッドオーバーレイを描画
 * 三分割法・黄金比・クロスヘアに対応
 */
@Composable
fun GridOverlay(
    gridType: GridType,
    modifier: Modifier = Modifier,
) {
    if (gridType == GridType.NONE) return

    val lineColor = Color.White.copy(alpha = 0.4f)
    val lineWidth = 1f

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        when (gridType) {
            GridType.RULE_OF_THIRDS -> {
                // 縦線 2本
                drawLine(lineColor, Offset(w / 3f, 0f), Offset(w / 3f, h), lineWidth)
                drawLine(lineColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), lineWidth)
                // 横線 2本
                drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), lineWidth)
                drawLine(lineColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), lineWidth)
            }
            GridType.GOLDEN_RATIO -> {
                val phi = 0.618f
                val x1 = w * (1f - phi)
                val x2 = w * phi
                val y1 = h * (1f - phi)
                val y2 = h * phi
                drawLine(lineColor, Offset(x1, 0f), Offset(x1, h), lineWidth)
                drawLine(lineColor, Offset(x2, 0f), Offset(x2, h), lineWidth)
                drawLine(lineColor, Offset(0f, y1), Offset(w, y1), lineWidth)
                drawLine(lineColor, Offset(0f, y2), Offset(w, y2), lineWidth)
            }
            GridType.CROSSHAIR -> {
                drawLine(lineColor, Offset(w / 2f, 0f), Offset(w / 2f, h), lineWidth)
                drawLine(lineColor, Offset(0f, h / 2f), Offset(w, h / 2f), lineWidth)
                // 中心の小さな円
                drawCircle(
                    color = lineColor,
                    radius = 20f,
                    center = Offset(w / 2f, h / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = lineWidth),
                )
            }
            GridType.NONE -> { /* no-op */ }
        }
    }
}
