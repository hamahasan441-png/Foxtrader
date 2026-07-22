package com.foxtrader.app.domain.model

/**
 * A single OHLCV price candle. The atomic unit of market data.
 * Immutable value object — belongs to the domain layer (framework-free).
 */
data class Candle(
    val timestamp: Long,   // epoch millis (bar open time)
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
) {
    val isBullish: Boolean get() = close >= open
    val bodyHigh: Double get() = maxOf(open, close)
    val bodyLow: Double get() = minOf(open, close)
    val range: Double get() = high - low
    val bodySize: Double get() = kotlin.math.abs(close - open)
}

/** Supported chart timeframes. */
enum class Timeframe(val label: String, val minutes: Int) {
    M1("1m", 1),
    M5("5m", 5),
    M15("15m", 15),
    M30("30m", 30),
    H1("1H", 60),
    H4("4H", 240),
    D1("1D", 1440),
    W1("1W", 10080),
    MN("1M", 43200);

    companion object {
        fun fromLabel(label: String): Timeframe = entries.firstOrNull { it.label == label } ?: M15
    }
}

/** Directional market bias. */
enum class Bias { BULLISH, BEARISH, NEUTRAL }

/** Trade direction. */
enum class Direction { BULLISH, BEARISH }
