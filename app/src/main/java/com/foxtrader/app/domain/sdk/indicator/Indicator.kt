package com.foxtrader.app.domain.sdk.indicator

import com.foxtrader.app.domain.model.Candle

/**
 * SDK interface for custom indicators.
 *
 * Any class implementing this interface can be registered in the
 * [IndicatorRegistry] and will be available on the chart overlay system.
 *
 * CONTRACT:
 * - [compute] is a pure function: no side effects, no state mutation.
 * - NON-REPAINTING: only reads candles[0..last]. The output at index i
 *   must depend solely on candles[0..i].
 * - Thread-safe: may be called from any dispatcher.
 * - Must not throw; return empty/partial results on insufficient data.
 */
interface Indicator {
    /** Unique identifier (e.g. "ema_20", "custom_rsi_divergence"). */
    val id: String
    /** Human-readable display name (e.g. "EMA 20"). */
    val displayName: String
    /** Short description shown in the indicator panel. */
    val description: String
    /** Whether this indicator draws on the price chart (overlay) or in a sub-panel. */
    val isOverlay: Boolean
    /** Version string for the indicator (for marketplace/registry tracking). */
    val version: String get() = "1.0.0"

    /**
     * Compute the indicator values for the given candle series.
     *
     * @param candles The full visible candle series (ascending by time).
     * @param params Optional user-configurable parameters (period, multiplier, etc.).
     * @return One or more named series of values, aligned with the candle indices.
     */
    fun compute(candles: List<Candle>, params: Map<String, Double> = emptyMap()): IndicatorResult
}

/**
 * The output of an indicator computation — one or more named line series.
 * Each series is a DoubleArray aligned with the input candle indices.
 */
data class IndicatorResult(
    /** Named series (e.g. "main", "upper", "lower" for Bollinger). */
    val series: Map<String, DoubleArray>,
    /** Optional signal markers (buy/sell dots at specific indices). */
    val signals: List<IndicatorSignal> = emptyList(),
) {
    /** Convenience for single-series indicators. */
    val main: DoubleArray? get() = series["main"]
}

data class IndicatorSignal(
    val index: Int,
    val type: SignalType,
    val price: Double,
    val label: String = "",
)

enum class SignalType { BUY, SELL, NEUTRAL }
