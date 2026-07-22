package com.foxtrader.app.domain.model

/**
 * Replay playback speed multiplier options.
 */
enum class ReplaySpeed(val label: String, val delayMs: Long) {
    SPEED_0_25("0.25x", 2000L),
    SPEED_0_5("0.5x", 1000L),
    SPEED_1("1x", 500L),
    SPEED_2("2x", 250L),
    SPEED_4("4x", 125L),
    SPEED_8("8x", 62L),
    SPEED_16("16x", 31L),
}

/**
 * Replay engine state — observable by the UI.
 */
data class ReplayState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val speed: ReplaySpeed = ReplaySpeed.SPEED_1,
    val currentIndex: Int = 0,
    val totalBars: Int = 0,
    val startIndex: Int = 0,       // Where replay started
    val visibleCandles: List<Candle> = emptyList(),
) {
    val progress: Float
        get() = if (totalBars > 0) currentIndex.toFloat() / totalBars else 0f

    val isPlaying: Boolean get() = isActive && !isPaused
}
