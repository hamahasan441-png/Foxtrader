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
 * end of [visibleCandles]. [startIndex] and [endIndex] are therefore exclusive
 * candle counts as well, which keeps old callers/tests source-compatible while
 * allowing a replay session to be hard-bounded to selected history.
 */
data class ReplayState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val speed: ReplaySpeed = ReplaySpeed.SPEED_1,
    val currentIndex: Int = 0,
    val totalBars: Int = 0,
    val startIndex: Int = 0,
    /** Exclusive replay boundary. 0 means no active bounded range. */
    val endIndex: Int = 0,
    val visibleCandles: List<Candle> = emptyList(),
) {
    val progress: Float
        get() {
            if (!isActive) return 0f
            val end = endIndex.takeIf { it > startIndex } ?: totalBars
            if (end <= startIndex) return 0f
            return ((currentIndex - startIndex).toFloat() / (end - startIndex).toFloat())
                .coerceIn(0f, 1f)
        }

    val isPlaying: Boolean get() = isActive && !isPaused
    val isAtRangeEnd: Boolean get() = isActive && endIndex > 0 && currentIndex >= endIndex
}
