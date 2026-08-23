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
 *
 * Indices use the existing replay convention: [currentIndex] is the exclusive
 * end of [visibleCandles]. [startIndex] is where replay started. [endIndex] is
 * an optional exclusive hard boundary used by selected-history replay; zero
 * keeps the legacy unbounded-to-dataset-end behaviour.
 */
data class ReplayState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val speed: ReplaySpeed = ReplaySpeed.SPEED_1,
    val currentIndex: Int = 0,
    val totalBars: Int = 0,
    val startIndex: Int = 0,
    /** Exclusive selected-history boundary; 0 for legacy whole-tail replay. */
    val endIndex: Int = 0,
    val visibleCandles: List<Candle> = emptyList(),
) {
    val progress: Float
        get() = when {
            !isActive -> 0f
            endIndex > startIndex -> ((currentIndex - startIndex).toFloat() / (endIndex - startIndex).toFloat())
                .coerceIn(0f, 1f)
            totalBars > 0 -> (currentIndex.toFloat() / totalBars.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

    val isPlaying: Boolean get() = isActive && !isPaused
    val isBounded: Boolean get() = isActive && endIndex > startIndex
    val isAtRangeEnd: Boolean get() = isBounded && currentIndex >= endIndex
}
