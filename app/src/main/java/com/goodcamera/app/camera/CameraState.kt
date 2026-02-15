package com.goodcamera.app.camera

/**
 * Represents all manual camera parameters the user can control.
 */
data class CameraSettings(
    val iso: Int = 100,
    val shutterSpeedNs: Long = 33_333_333L, // 1/30s
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val focusDistance: Float = 0f, // 0 = infinity, diopters
    val autoExposure: Boolean = true,
    val autoFocus: Boolean = true,
    val exposureCompensation: Int = 0,
)

enum class WhiteBalanceMode(val label: String, val kelvin: Int) {
    AUTO("Auto", 0),
    DAYLIGHT("Daylight", 5500),
    CLOUDY("Cloudy", 6500),
    TUNGSTEN("Tungsten", 2850),
    FLUORESCENT("Fluorescent", 4000),
    SHADE("Shade", 7500),
}

enum class CaptureMode(val label: String) {
    AI_AUTO("AI"),
    AUTO("Auto"),
    PRO("Pro"),
    HDR("HDR"),
    NIGHT("Night"),
    BURST("Burst"),
}

enum class OutputFormat(val label: String) {
    JPEG("JPEG"),
    RAW_DNG("RAW+JPEG"),
}

data class CameraCapabilities(
    val isoRange: IntRange = 100..3200,
    val shutterSpeedRangeNs: LongRange = 1_000_000L..1_000_000_000L, // 1ms - 1s
    val minFocusDistance: Float = 0f,
    val supportsRaw: Boolean = false,
    val supportedIsos: List<Int> = listOf(100, 200, 400, 800, 1600, 3200),
)

enum class GridType(val label: String) {
    NONE("OFF"),
    RULE_OF_THIRDS("3x3"),
    GOLDEN_RATIO("Golden"),
    CROSSHAIR("+"),
}

enum class AppScreen {
    CAMERA,
    GALLERY,
    SETTINGS,
}

data class CameraUiState(
    val isPreviewActive: Boolean = false,
    val isCaptureInProgress: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.AI_AUTO,
    val outputFormat: OutputFormat = OutputFormat.JPEG,
    val settings: CameraSettings = CameraSettings(),
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val lastCapturedPath: String? = null,
    val errorMessage: String? = null,
    val usingFrontCamera: Boolean = false,
    val hdrFrameCount: Int = 3,
    val nightFrameCount: Int = 8,
    val showReviewScreen: Boolean = false,
    val reviewImagePath: String? = null,
    val autoContrastEnabled: Boolean = true,
    val zoomLevel: Float = 1f,
    val maxZoom: Float = 1f,
    val gridType: GridType = GridType.NONE,
    val timerSeconds: Int = 0,
    val timerCountdown: Int = 0,
    val currentScreen: AppScreen = AppScreen.CAMERA,
    // AI Auto Mode
    val aiDetectedScene: String = "",
    val aiConfidence: Float = 0f,
    val aiAnalyzing: Boolean = false,
    // Burst Mode
    val isBurstActive: Boolean = false,
    val burstCount: Int = 0,
    val burstSavedPaths: List<String> = emptyList(),
)
