package com.foxtrader.app.domain.usecase.mtf

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.tradepro.TimeframeResampler

/**
 * A higher timeframe derived from the execution series, with the moment each of
 * its bars became knowable.
 *
 * Shared by every multi-timeframe engine, so they cannot drift into different
 * answers about when a higher-timeframe bar became usable.
 *
 * This is the whole multi-timeframe contract in one place. Resampling upward is
 * the only direction that adds no information the execution bars did not
 * already carry, so no second data fetch is needed and chart, replay and
 * backtest all see the same higher timeframe.
 *
 * The trailing partial bucket is dropped, and every retained bar records the
 * execution index at which it closed. An unfinished higher-timeframe bar's high
 * and low keep moving as the execution series advances, so treating one as
 * final — or reading a finished one before the execution series reached its
 * close — is a direct look-ahead.
 */
class MultiTimeframeSeries private constructor(
    val timeframe: Timeframe,
    val candles: List<Candle>,
    /** For each bar in [candles], the execution index at which it closed. */
    private val closedAt: IntArray,
) {

    val isEmpty: Boolean get() = candles.isEmpty()

    /** Execution index at which bar [index] became knowable. */
    fun knownFrom(index: Int): Int = closedAt[index]

    /**
     * The number of bars already closed at [executionIndex].
     *
     * Callers slice with this rather than filtering, so a detector can never be
     * handed a bar the execution series had not yet reached.
     */
    fun countClosedAt(executionIndex: Int): Int {
        var low = 0
        var high = candles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (closedAt[mid] <= executionIndex) low = mid + 1 else high = mid
        }
        return low
    }

    /** The prefix of bars closed at [executionIndex]. */
    fun closedPrefix(executionIndex: Int): List<Candle> {
        val count = countClosedAt(executionIndex)
        return if (count == candles.size) candles else candles.subList(0, count)
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        /**
         * Build [target] from [execution].
         *
         * Returns an empty series when the execution series is empty or the
         * target is not strictly higher, rather than pretending a same-or-lower
         * resample carries independent information.
         */
        fun from(
            execution: List<Candle>,
            executionTimeframe: Timeframe,
            target: Timeframe,
        ): MultiTimeframeSeries {
            if (execution.isEmpty() || target.minutes <= executionTimeframe.minutes) {
                return MultiTimeframeSeries(target, emptyList(), IntArray(0))
            }

            val resampled = TimeframeResampler.resample(execution, target)
            if (resampled.isEmpty()) return MultiTimeframeSeries(target, emptyList(), IntArray(0))

            val executionDuration = executionTimeframe.minutes.toLong() * MILLIS_PER_MINUTE
            val targetDuration = target.minutes.toLong() * MILLIS_PER_MINUTE

            val kept = ArrayList<Candle>(resampled.size)
            val closed = ArrayList<Int>(resampled.size)

            // Execution bars are ordered, so one forward pass finds each
            // bucket's closing bar without rescanning the series per bucket.
            var cursor = 0
            for (bar in resampled) {
                val bucketEnd = bar.timestamp + targetDuration
                while (cursor < execution.size &&
                    execution[cursor].timestamp + executionDuration < bucketEnd
                ) {
                    cursor++
                }
                if (cursor >= execution.size) break
                kept += bar
                closed += cursor
            }

            return MultiTimeframeSeries(target, kept, closed.toIntArray())
        }
    }
}
