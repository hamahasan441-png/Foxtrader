package com.foxtrader.app.domain.model

/**
 * How the chart transforms the underlying time candles before rendering.
 *
 * These modes operate purely on the existing candle series (no tick feed
 * required), which is why they are always available regardless of the data
 * provider:
 * - [TIME]         Raw time candles, unchanged.
 * - [HEIKIN_ASHI]  Smoothed candles that filter noise and clarify trend. Keeps
 *                  the same timestamps/indices as the source, so all overlays
 *                  (order blocks, FVG, structure, …) stay aligned.
 * - [RENKO]        Fixed-height price bricks; time and small noise are removed.
 *                  The series length/indices change, so overlays are hidden in
 *                  this mode.
 *
 * Tick-driven modes (volume bars, tick bars) live in the tick-engine builders
 * and require a tick feed; they are intentionally not part of this candle-only
 * enum.
 */
enum class ChartBarMode(val label: String, val preservesTimeAxis: Boolean) {
    TIME("Time", preservesTimeAxis = true),
    HEIKIN_ASHI("Heikin-Ashi", preservesTimeAxis = true),
    RENKO("Renko", preservesTimeAxis = false),
}
