package com.foxtrader.app.domain.model

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

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
@Serializable
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

    /**
     * Open time of the bucket that contains [timestamp].
     *
     * Intraday and daily bars are fixed widths from the Unix epoch, which is
     * midnight UTC, so they line up with the clock. Weeks start Monday 00:00
     * UTC and months start on the first of the calendar month. Dividing by
     * `minutes` instead would pin weeks to Thursday and months to a fictional
     * 30-day cycle.
     */
    fun barOpenMillis(timestamp: Long): Long {
        val durationMs = minutes.toLong() * 60_000L
        return when (this) {
            M1, M5, M15, M30, H1, H4, D1 -> Math.floorDiv(timestamp, durationMs) * durationMs
            W1 -> {
                val utc = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC)
                val daysFromMonday = (utc.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()
                utc.toLocalDate().minusDays(daysFromMonday)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            }
            MN -> Instant.ofEpochMilli(timestamp)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }
    }

    companion object {
        fun fromLabel(label: String): Timeframe = entries.firstOrNull { it.label == label } ?: M15
    }
}

/** Directional market bias. */
enum class Bias { BULLISH, BEARISH, NEUTRAL }

/** Trade direction. */
enum class Direction { BULLISH, BEARISH }
