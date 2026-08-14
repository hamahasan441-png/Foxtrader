package com.foxtrader.app.data.remote.websocket

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * Converts Polygon's minute aggregate stream into the chart's requested
 * timeframe without fabricating gaps.
 *
 * A forming bucket may be emitted repeatedly, but once a later bucket arrives
 * the previous candle is sealed and never mutated again. Out-of-order minute
 * bars older than the current bucket are ignored. This keeps the live path
 * aligned with the app's no-repainting and missing-candle invariants. Polygon's
 * minute volume is cumulative while a minute is forming, so repeated updates
 * replace the M1 volume or add only the delta to a higher-timeframe bucket.
 */
internal class PolygonCandleAggregator {

    data class Update(
        val candle: Candle,
        val isBarClose: Boolean,
    )

    private data class Key(val symbol: String, val timeframe: Timeframe)

    private data class State(
        val candle: Candle,
        val lastMinuteTimestamp: Long,
        val lastMinuteVolume: Double,
    )

    private val current = mutableMapOf<Key, State>()

    fun update(
        symbol: String,
        timeframe: Timeframe,
        minuteCandle: Candle,
    ): List<Update> {
        val key = Key(symbol, timeframe)
        val bucketStart = bucketStart(minuteCandle.timestamp, timeframe)
        val previous = current[key]

        if (previous == null) {
            val forming = minuteCandle.copy(timestamp = bucketStart)
            current[key] = State(
                candle = forming,
                lastMinuteTimestamp = minuteCandle.timestamp,
                lastMinuteVolume = minuteCandle.volume,
            )
            return listOf(Update(forming, isBarClose = false))
        }

        if (minuteCandle.timestamp < previous.lastMinuteTimestamp) return emptyList()

        return when {
            bucketStart == previous.candle.timestamp -> {
                val forming = previous.candle.merge(
                    minute = minuteCandle,
                    volumeDelta = volumeDelta(previous, minuteCandle),
                    replaceVolume = timeframe == Timeframe.M1,
                )
                current[key] = State(
                    candle = forming,
                    lastMinuteTimestamp = minuteCandle.timestamp,
                    lastMinuteVolume = minuteCandle.volume,
                )
                listOf(Update(forming, isBarClose = false))
            }
            bucketStart > previous.candle.timestamp -> {
                val forming = minuteCandle.copy(timestamp = bucketStart)
                current[key] = State(
                    candle = forming,
                    lastMinuteTimestamp = minuteCandle.timestamp,
                    lastMinuteVolume = minuteCandle.volume,
                )
                listOf(
                    Update(previous.candle, isBarClose = true),
                    Update(forming, isBarClose = false),
                )
            }
            else -> emptyList()
        }
    }

    fun remove(symbol: String, timeframe: Timeframe) {
        current.remove(Key(symbol, timeframe))
    }

    fun clear() {
        current.clear()
    }

    private fun bucketStart(timestamp: Long, timeframe: Timeframe): Long {
        val instant = Instant.ofEpochMilli(timestamp)
        val utc = instant.atZone(ZoneOffset.UTC)
        return when (timeframe) {
            Timeframe.M1 -> timestamp
            Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1, Timeframe.H4 -> {
                val bucketMs = timeframe.minutes.toLong() * MINUTE_MS
                Math.floorDiv(timestamp, bucketMs) * bucketMs
            }
            Timeframe.D1 -> utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            Timeframe.W1 -> {
                val monday = utc.toLocalDate().minusDays((utc.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
                monday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
            Timeframe.MN -> {
                utc.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        }
    }

    private fun volumeDelta(previous: State, minute: Candle): Double =
        if (minute.timestamp == previous.lastMinuteTimestamp) {
            (minute.volume - previous.lastMinuteVolume).coerceAtLeast(0.0)
        } else {
            minute.volume
        }

    private fun Candle.merge(
        minute: Candle,
        volumeDelta: Double,
        replaceVolume: Boolean,
    ): Candle = copy(
        high = maxOf(high, minute.high),
        low = minOf(low, minute.low),
        close = minute.close,
        volume = if (replaceVolume) minute.volume else volume + volumeDelta,
    )

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
