package com.foxtrader.app.domain.usecase.backtest

import com.foxtrader.app.domain.model.Candle

/**
 * Closed-bar historical test window used by on-chart research and replay.
 *
 * The strategy still receives the full prefix from candle 0 through the current
 * bar, preserving indicator/structure warm-up exactly as live code would see it.
 * Entries are admitted only inside [startIndex]..[endIndex], and the dataset is
 * truncated at [endIndex], so no trade can use bars beyond the selected history.
 */
data class HistoricalTestWindow(
    val startIndex: Int,
    val endIndex: Int,
) {
    init {
        require(startIndex >= 0) { "startIndex must be >= 0" }
        require(endIndex >= startIndex) { "endIndex must be >= startIndex" }
    }

    val barCount: Int get() = endIndex - startIndex + 1

    fun clampTo(candles: List<Candle>): HistoricalTestWindow {
        require(candles.isNotEmpty()) { "Historical test requires candles." }
        val end = endIndex.coerceIn(0, candles.lastIndex)
        val start = startIndex.coerceIn(0, end)
        return HistoricalTestWindow(start, end)
    }

    companion object {
        fun visible(startIndex: Float, visibleBars: Float, lastIndex: Int): HistoricalTestWindow {
            require(lastIndex >= 0) { "Visible range requires at least one candle." }
            val start = kotlin.math.floor(startIndex.toDouble()).toInt().coerceIn(0, lastIndex)
            val count = kotlin.math.ceil(visibleBars.toDouble()).toInt().coerceAtLeast(1)
            val end = (start + count - 1).coerceIn(start, lastIndex)
            return HistoricalTestWindow(start, end)
        }
    }
}
