package com.foxtrader.app.domain.model

/**
 * Bar-mode selector: determines how raw candles are rendered on the chart.
 *
 * [TIME] — standard time-based bars (unchanged candles).
 * [HEIKIN_ASHI] — Heikin-Ashi smoothed candles (preserves the time axis).
 * [RENKO] — Renko bricks (time axis is NOT preserved).
 */
enum class ChartBarMode(val label: String, val preservesTimeAxis: Boolean) {
    TIME("Time", preservesTimeAxis = true),
    HEIKIN_ASHI("Heikin-Ashi", preservesTimeAxis = true),
    RENKO("Renko", preservesTimeAxis = false),
}
