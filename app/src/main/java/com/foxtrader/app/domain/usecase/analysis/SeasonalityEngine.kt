package com.foxtrader.app.domain.usecase.analysis

import com.foxtrader.app.domain.model.Candle
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject

/**
 * Seasonality Engine — detects recurring time-based patterns.
 *
 * Analyzes historical returns by:
 * - Day of week (Monday effect, Friday close, etc.)
 * - Hour of day (session momentum)
 *
 * Helps identify statistically favorable trading windows.
 */
class SeasonalityEngine @Inject constructor() {

    data class SeasonalStat(
        val bucket: String,       // e.g. "Monday", "14:00"
        val averageReturn: Double,
        val winRate: Double,      // % of periods that closed positive
        val sampleSize: Int,
    )

    private val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** Average return + win rate grouped by day of week. */
    fun byDayOfWeek(candles: List<Candle>): List<SeasonalStat> {
        if (candles.size < 2) return emptyList()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val buckets = HashMap<Int, MutableList<Double>>()

        for (i in 1 until candles.size) {
            cal.timeInMillis = candles[i].timestamp
            val day = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sun
            val ret = if (candles[i - 1].close > 0) {
                (candles[i].close - candles[i - 1].close) / candles[i - 1].close * 100.0
            } else 0.0
            buckets.getOrPut(day) { mutableListOf() }.add(ret)
        }

        return buckets.entries.sortedBy { it.key }.map { (day, returns) ->
            SeasonalStat(
                bucket = dayNames.getOrElse(day) { "?" },
                averageReturn = returns.average(),
                winRate = returns.count { it > 0 }.toDouble() / returns.size * 100.0,
                sampleSize = returns.size,
            )
        }
    }

    /** Average return + win rate grouped by hour of day (UTC). */
    fun byHourOfDay(candles: List<Candle>): List<SeasonalStat> {
        if (candles.size < 2) return emptyList()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val buckets = HashMap<Int, MutableList<Double>>()

        for (i in 1 until candles.size) {
            cal.timeInMillis = candles[i].timestamp
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val ret = if (candles[i - 1].close > 0) {
                (candles[i].close - candles[i - 1].close) / candles[i - 1].close * 100.0
            } else 0.0
            buckets.getOrPut(hour) { mutableListOf() }.add(ret)
        }

        return buckets.entries.sortedBy { it.key }.map { (hour, returns) ->
            SeasonalStat(
                bucket = String.format("%02d:00", hour),
                averageReturn = returns.average(),
                winRate = returns.count { it > 0 }.toDouble() / returns.size * 100.0,
                sampleSize = returns.size,
            )
        }
    }

    /** The best and worst performing time buckets. */
    fun bestWorst(stats: List<SeasonalStat>): Pair<SeasonalStat?, SeasonalStat?> =
        stats.maxByOrNull { it.averageReturn } to stats.minByOrNull { it.averageReturn }
}
