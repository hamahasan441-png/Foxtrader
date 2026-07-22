package com.foxtrader.app.domain.usecase.indicators

import com.foxtrader.app.domain.model.Candle
import javax.inject.Inject

/**
 * Pivot Points calculator — key intraday support/resistance levels.
 *
 * Supports 4 calculation methods used by institutional desks:
 * - Classic (Floor Trader Pivots)
 * - Fibonacci
 * - Camarilla
 * - Woodie
 *
 * Pivots use the PREVIOUS period's High/Low/Close to project the current
 * period's levels — inherently non-repainting.
 */
class PivotPoints @Inject constructor() {

    enum class Method { CLASSIC, FIBONACCI, CAMARILLA, WOODIE }

    data class PivotLevels(
        val method: Method,
        val pivot: Double,
        val r1: Double, val r2: Double, val r3: Double,
        val s1: Double, val s2: Double, val s3: Double,
    )

    /**
     * Compute pivot levels from the previous period's OHLC.
     * @param prevHigh previous period high
     * @param prevLow previous period low
     * @param prevClose previous period close
     * @param prevOpen previous period open (only needed for WOODIE)
     */
    fun calculate(
        prevHigh: Double,
        prevLow: Double,
        prevClose: Double,
        prevOpen: Double = prevClose,
        method: Method = Method.CLASSIC,
    ): PivotLevels {
        val range = (prevHigh - prevLow).coerceAtLeast(1e-9)
        return when (method) {
            Method.CLASSIC -> {
                val p = (prevHigh + prevLow + prevClose) / 3.0
                PivotLevels(
                    method, p,
                    r1 = 2 * p - prevLow,
                    r2 = p + range,
                    r3 = prevHigh + 2 * (p - prevLow),
                    s1 = 2 * p - prevHigh,
                    s2 = p - range,
                    s3 = prevLow - 2 * (prevHigh - p),
                )
            }
            Method.FIBONACCI -> {
                val p = (prevHigh + prevLow + prevClose) / 3.0
                PivotLevels(
                    method, p,
                    r1 = p + 0.382 * range,
                    r2 = p + 0.618 * range,
                    r3 = p + 1.000 * range,
                    s1 = p - 0.382 * range,
                    s2 = p - 0.618 * range,
                    s3 = p - 1.000 * range,
                )
            }
            Method.CAMARILLA -> {
                val p = (prevHigh + prevLow + prevClose) / 3.0
                PivotLevels(
                    method, p,
                    r1 = prevClose + range * 1.1 / 12.0,
                    r2 = prevClose + range * 1.1 / 6.0,
                    r3 = prevClose + range * 1.1 / 4.0,
                    s1 = prevClose - range * 1.1 / 12.0,
                    s2 = prevClose - range * 1.1 / 6.0,
                    s3 = prevClose - range * 1.1 / 4.0,
                )
            }
            Method.WOODIE -> {
                val p = (prevHigh + prevLow + 2 * prevOpen) / 4.0
                PivotLevels(
                    method, p,
                    r1 = 2 * p - prevLow,
                    r2 = p + range,
                    r3 = prevHigh + 2 * (p - prevLow),
                    s1 = 2 * p - prevHigh,
                    s2 = p - range,
                    s3 = prevLow - 2 * (prevHigh - p),
                )
            }
        }
    }

    /**
     * Compute daily pivots from a full candle series by grouping bars into
     * UTC days and using each completed day's OHLC for the next day's levels.
     * Returns the most recent day's pivots (or null if <2 days of data).
     */
    fun calculateDaily(candles: List<Candle>, method: Method = Method.CLASSIC): PivotLevels? {
        if (candles.size < 2) return null
        val byDay = candles.groupBy { it.timestamp / 86_400_000L }.toSortedMap()
        if (byDay.size < 2) return null
        // Use the second-to-last completed day to project the last day.
        val days = byDay.values.toList()
        val prev = days[days.size - 2]
        return calculate(
            prevHigh = prev.maxOf { it.high },
            prevLow = prev.minOf { it.low },
            prevClose = prev.last().close,
            prevOpen = prev.first().open,
            method = method,
        )
    }
}
