package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe

/**
 * Fail-closed validation for signal engines. Price-action engines should never
 * manufacture a setup from malformed, duplicated or out-of-order OHLC data.
 */
object SignalSeriesIntegrity {
    data class Result(val valid: Boolean, val reason: String? = null)

    fun validate(candles: List<Candle>, minimumBars: Int = 1): Result {
        if (candles.size < minimumBars) return Result(false, "Need at least $minimumBars bars.")
        var previousTs = Long.MIN_VALUE
        candles.forEachIndexed { index, c ->
            if (c.timestamp <= previousTs) return Result(false, "Timestamps are duplicated or not strictly increasing at $index.")
            previousTs = c.timestamp
            if (!c.open.isFinite() || !c.high.isFinite() || !c.low.isFinite() || !c.close.isFinite() || !c.volume.isFinite()) {
                return Result(false, "Non-finite OHLCV at $index.")
            }
            if (c.open <= 0.0 || c.high <= 0.0 || c.low <= 0.0 || c.close <= 0.0 || c.volume < 0.0) {
                return Result(false, "Invalid OHLCV domain at $index.")
            }
            if (c.high < c.low || c.open !in c.low..c.high || c.close !in c.low..c.high) {
                return Result(false, "Invalid OHLC geometry at $index.")
            }
        }
        return Result(true)
    }
}

/**
 * Maps a live candle list to its closed-bar prefix. Candle timestamps are bar
 * OPEN times; therefore a bar is confirmed only after one complete timeframe.
 * Historical/replay bars naturally remain confirmed because their close time is
 * in the past. No future-bar access is introduced.
 */
object ConfirmedBarPolicy {
    fun latestConfirmedIndex(candles: List<Candle>, timeframe: Timeframe, nowMillis: Long): Int {
        if (candles.isEmpty()) return -1
        val duration = timeframe.minutes.toLong().coerceAtLeast(1L) * 60_000L
        var last = -1
        for (i in candles.indices) {
            val closeTime = safeAdd(candles[i].timestamp, duration)
            if (closeTime <= nowMillis) last = i else break
        }
        return last
    }

    fun confirmedPrefix(candles: List<Candle>, timeframe: Timeframe, nowMillis: Long): List<Candle> {
        val last = latestConfirmedIndex(candles, timeframe, nowMillis)
        return if (last < 0) emptyList() else candles.subList(0, last + 1)
    }

    fun confirmedMap(
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        nowMillis: Long,
    ): Map<Timeframe, List<Candle>> = candlesByTimeframe.mapNotNull { (timeframe, candles) ->
        confirmedPrefix(candles, timeframe, nowMillis).takeIf { it.isNotEmpty() }?.let { timeframe to it }
    }.toMap()

    private fun safeAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
}
