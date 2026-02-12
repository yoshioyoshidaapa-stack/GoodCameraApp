package com.goodcamera.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodcamera.app.camera.CameraCapabilities
import com.goodcamera.app.camera.CameraSettings
import com.goodcamera.app.camera.WhiteBalanceMode
import com.goodcamera.app.util.ShutterSpeedFormatter

/**
 * Proモード用の手動制御パネル
 * ISO、シャッタースピード、ホワイトバランス、フォーカス距離を制御
 */
@Composable
fun ProControlsPanel(
    settings: CameraSettings,
    capabilities: CameraCapabilities,
    onIsoChanged: (Int) -> Unit,
    onShutterSpeedChanged: (Long) -> Unit,
    onWhiteBalanceChanged: (WhiteBalanceMode) -> Unit,
    onFocusDistanceChanged: (Float) -> Unit,
    onAutoExposureChanged: (Boolean) -> Unit,
    onAutoFocusChanged: (Boolean) -> Unit,
    onExposureCompChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedSection by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC1A1A1A))
            .padding(8.dp),
    ) {
        // セクションボタン行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ControlChip("ISO", settings.iso.toString(), expandedSection == "iso") {
                expandedSection = if (expandedSection == "iso") null else "iso"
            }
            ControlChip("SS", ShutterSpeedFormatter.format(settings.shutterSpeedNs),
                expandedSection == "ss") {
                expandedSection = if (expandedSection == "ss") null else "ss"
            }
            ControlChip("WB", settings.whiteBalance.label, expandedSection == "wb") {
                expandedSection = if (expandedSection == "wb") null else "wb"
            }
            ControlChip("MF", if (settings.autoFocus) "Auto" else String.format("%.1f", settings.focusDistance),
                expandedSection == "focus") {
                expandedSection = if (expandedSection == "focus") null else "focus"
            }
            ControlChip("EV", "${if (settings.exposureCompensation >= 0) "+" else ""}${settings.exposureCompensation}",
                expandedSection == "ev") {
                expandedSection = if (expandedSection == "ev") null else "ev"
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 展開セクション
        when (expandedSection) {
            "iso" -> IsoSelector(
                currentIso = settings.iso,
                availableIsos = capabilities.supportedIsos,
                autoExposure = settings.autoExposure,
                onIsoSelected = onIsoChanged,
                onAutoChanged = onAutoExposureChanged,
            )
            "ss" -> ShutterSpeedSelector(
                currentSpeed = settings.shutterSpeedNs,
                speedRange = capabilities.shutterSpeedRangeNs,
                onSpeedSelected = onShutterSpeedChanged,
            )
            "wb" -> WhiteBalanceSelector(
                currentWb = settings.whiteBalance,
                onWbSelected = onWhiteBalanceChanged,
            )
            "focus" -> FocusControl(
                focusDistance = settings.focusDistance,
                maxDistance = capabilities.minFocusDistance,
                autoFocus = settings.autoFocus,
                onDistanceChanged = onFocusDistanceChanged,
                onAutoChanged = onAutoFocusChanged,
            )
            "ev" -> ExposureCompensationControl(
                ev = settings.exposureCompensation,
                onEvChanged = onExposureCompChanged,
            )
        }
    }
}

@Composable
private fun ControlChip(
    label: String,
    value: String,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun IsoSelector(
    currentIso: Int,
    availableIsos: List<Int>,
    autoExposure: Boolean,
    onIsoSelected: (Int) -> Unit,
    onAutoChanged: (Boolean) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("自動露出", color = Color.White, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = autoExposure,
                onCheckedChange = onAutoChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        if (!autoExposure) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                items(availableIsos) { iso ->
                    ValueChip(
                        text = iso.toString(),
                        isSelected = iso == currentIso,
                        onClick = { onIsoSelected(iso) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutterSpeedSelector(
    currentSpeed: Long,
    speedRange: LongRange,
    onSpeedSelected: (Long) -> Unit,
) {
    val availableSpeeds = ShutterSpeedFormatter.availableSpeeds(speedRange)

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(availableSpeeds) { speed ->
            ValueChip(
                text = ShutterSpeedFormatter.format(speed),
                isSelected = speed == currentSpeed,
                onClick = { onSpeedSelected(speed) },
            )
        }
    }
}

@Composable
private fun WhiteBalanceSelector(
    currentWb: WhiteBalanceMode,
    onWbSelected: (WhiteBalanceMode) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        items(WhiteBalanceMode.entries.toList()) { wb ->
            ValueChip(
                text = wb.label,
                isSelected = wb == currentWb,
                onClick = { onWbSelected(wb) },
            )
        }
    }
}

@Composable
private fun FocusControl(
    focusDistance: Float,
    maxDistance: Float,
    autoFocus: Boolean,
    onDistanceChanged: (Float) -> Unit,
    onAutoChanged: (Boolean) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("オートフォーカス", color = Color.White, fontSize = 12.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = autoFocus,
                onCheckedChange = onAutoChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        if (!autoFocus && maxDistance > 0f) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("∞", color = Color.White, fontSize = 14.sp)
                Slider(
                    value = focusDistance,
                    onValueChange = onDistanceChanged,
                    valueRange = 0f..maxDistance,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text("近", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ExposureCompensationControl(
    ev: Int,
    onEvChanged: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(
            "露出補正: ${if (ev >= 0) "+" else ""}$ev EV",
            color = Color.White,
            fontSize = 12.sp,
        )
        Slider(
            value = ev.toFloat(),
            onValueChange = { onEvChanged(it.toInt()) },
            valueRange = -4f..4f,
            steps = 7,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun ValueChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else Color(0xFF333333)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
