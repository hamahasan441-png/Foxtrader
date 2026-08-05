package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transforms standard OHLCV candles into Heikin-Ashi candles.
 *
 * Heikin-Ashi formulas:
 * - HA Close = (Open + High + Low + Close) / 4
 * - HA Open  = (prev HA Open + prev HA Close) / 2  (first bar uses standard open)
 * - HA High  = max(High, HA Open, HA Close)
 * - HA Low   = min(Low, HA Open, HA Close)
 *
 * Volume and timestamp are preserved unchanged.
 */
@Singleton
class HeikinAshiTransformer @Inject constructor() {

    fun transform(candles: List<Candle>): List<Candle> {
        if (candles.isEmpty()) return emptyList()

        val result = ArrayList<Candle>(candles.size)
        var prevHaOpen = candles[0].open
        var prevHaClose = (candles[0].open + candles[0].high + candles[0].low + candles[0].close) / 4.0

        for (i in candles.indices) {
            val c = candles[i]
            val haClose = (c.open + c.high + c.low + c.close) / 4.0
            val haOpen = if (i == 0) c.open else (prevHaOpen + prevHaClose) / 2.0
            val haHigh = maxOf(c.high, haOpen, haClose)
            val haLow = minOf(c.low, haOpen, haClose)

            result.add(
                Candle(
                    timestamp = c.timestamp,
                    open = haOpen,
                    high = haHigh,
                    low = haLow,
                    close = haClose,
                    volume = c.volume,
                )
            )

            prevHaOpen = haOpen
            prevHaClose = haClose
        }

        return result
    }
}
