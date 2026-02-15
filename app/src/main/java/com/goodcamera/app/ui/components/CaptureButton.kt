package com.goodcamera.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * シャッターボタンとカメラ切替ボタン
 */
@Composable
fun CaptureControls(
    isCaptureInProgress: Boolean,
    onCapture: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
    isBurstMode: Boolean = false,
    isBurstActive: Boolean = false,
    burstCount: Int = 0,
    onBurstStart: () -> Unit = {},
    onBurstStop: () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左側スペーサー
        Spacer(modifier = Modifier.size(48.dp))

        // シャッターボタン
        if (isBurstMode) {
            BurstShutterButton(
                isBurstActive = isBurstActive,
                burstCount = burstCount,
                onBurstStart = onBurstStart,
                onBurstStop = onBurstStop,
            )
        } else {
            ShutterButton(
                isCapturing = isCaptureInProgress,
                onClick = onCapture,
            )
        }

        // カメラ切替ボタン
        IconButton(
            onClick = onSwitchCamera,
            modifier = Modifier.size(48.dp),
            enabled = !isBurstActive,
        ) {
            Icon(
                imageVector = Icons.Filled.Cameraswitch,
                contentDescription = "カメラ切替",
                tint = if (isBurstActive) Color.White.copy(alpha = 0.3f) else Color.White,
                modifier = Modifier.size(32.dp),
            )
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
                    else Color.White
                ),
        )
    }
}

/**
 * 連写用シャッターボタン: 長押しで連写開始、離すと停止。
 * タップでも1回連写トリガー（短い連写）。
 */
@Composable
private fun BurstShutterButton(
    isBurstActive: Boolean,
    burstCount: Int,
    onBurstStart: () -> Unit,
    onBurstStop: () -> Unit,
) {
    val pulseAnim = rememberInfiniteTransition(label = "burstPulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "burstPulseScale",
    )

    val displayScale = if (isBurstActive) pulseScale else 1f

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(displayScale)
                .clip(CircleShape)
                .border(
                    width = if (isBurstActive) 4.dp else 3.dp,
                    color = if (isBurstActive) Color.Red else Color.White,
                    shape = CircleShape,
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onBurstStart()
                            // 指が離れるまで待機
                            tryAwaitRelease()
                            onBurstStop()
                        },
                    )
                }
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(
                        if (isBurstActive) Color.Red.copy(alpha = 0.8f)
                        else Color.White
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isBurstActive && burstCount > 0) {
                    Text(
                        text = burstCount.toString(),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
