package com.foxtrader.app.domain.usecase.keystone

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.keystone.model.KeystoneBiasRead
import com.foxtrader.app.domain.usecase.mtf.MultiTimeframeSeries
import java.util.Calendar
import java.util.TimeZone

/**
 * Step 1 — trade only with confirmed higher-timeframe structure and session
 * direction.
 *
 * Two separate reads have to agree before anything else is looked at.
 *
 * **Structure**, taken from the timeframes above the execution series. Only
 * bars that had closed by the execution bar in question are read, so the bias
 * moves when the higher timeframe moves and not a bar sooner. This is the whole
 * reason [MultiTimeframeSeries] exists: an unfinished higher-timeframe bar's
 * high and low keep changing, and a bias read from one is a look-ahead wearing
 * a timeframe label.
 *
 * **Session direction**, measured from where the current session opened to
 * where price stands now. It is a crude read and it is meant to be: its job is
 * only to stop the engine buying a sweep on a day the session has spent
 * entirely selling.
 */
class KeystoneBias(
    private val analyzeStructure: AnalyzeMarketStructureUseCase = AnalyzeMarketStructureUseCase(),
) {

    /**
     * The read usable at [executionIndex], or null when the higher timeframe
     * has not yet closed enough bars to have a structure of its own.
     */
    fun readAt(
        executionIndex: Int,
        candles: List<Candle>,
        higher: MultiTimeframeSeries,
        mid: MultiTimeframeSeries,
        config: KeystoneConfig,
    ): KeystoneBiasRead? {
        val sessionDirection = sessionDirectionAt(executionIndex, candles)

        if (config.biasMode == KeystoneBiasMode.NONE) {
            return KeystoneBiasRead(
                bias = Bias.NEUTRAL,
                higherTimeframe = higher.timeframe,
                midTimeframe = mid.timeframe,
                sessionDirection = sessionDirection,
                reason = "Bias filter disabled; both directions eligible.",
            )
        }

        val higherBars = higher.closedPrefix(executionIndex)
        if (higherBars.size < config.htfSwingLeft + config.htfSwingRight + MIN_STRUCTURE_BARS) return null

        val higherBias = analyzeStructure(
            candles = higherBars,
            leftBars = config.htfSwingLeft,
            rightBars = config.htfSwingRight,
        ).bias
        if (higherBias == Bias.NEUTRAL) return null

        if (config.biasMode == KeystoneBiasMode.HTF_AND_MTF_AGREE) {
            val midBars = mid.closedPrefix(executionIndex)
            if (midBars.size < config.mtfSwingLeft + config.mtfSwingRight + MIN_STRUCTURE_BARS) return null
            val midBias = analyzeStructure(
                candles = midBars,
                leftBars = config.mtfSwingLeft,
                rightBars = config.mtfSwingRight,
            ).bias
            if (midBias != higherBias) return null

            return KeystoneBiasRead(
                bias = higherBias,
                higherTimeframe = higher.timeframe,
                midTimeframe = mid.timeframe,
                sessionDirection = sessionDirection,
                reason = "${higher.timeframe.label} and ${mid.timeframe.label} both " +
                    higherBias.name.lowercase(),
            )
        }

        return KeystoneBiasRead(
            bias = higherBias,
            higherTimeframe = higher.timeframe,
            midTimeframe = mid.timeframe,
            sessionDirection = sessionDirection,
            reason = "${higher.timeframe.label} structure ${higherBias.name.lowercase()}",
        )
    }

    /** True when [direction] is allowed to trade under [read]. */
    fun permits(read: KeystoneBiasRead, direction: Direction, config: KeystoneConfig): Boolean {
        val structureOk = when (read.bias) {
            Bias.NEUTRAL -> config.biasMode == KeystoneBiasMode.NONE
            Bias.BULLISH -> direction == Direction.BULLISH
            Bias.BEARISH -> direction == Direction.BEARISH
        }
        if (!structureOk) return false
        if (!config.requireSessionAlignment) return true
        // An unknown session direction is not an endorsement. With alignment
        // demanded and nothing to align to, the setup waits.
        val session = read.sessionDirection ?: return false
        return session == direction
    }

    /**
     * Direction the current UTC day has travelled up to [index].
     *
     * Null before the session has produced enough bars to have a direction at
     * all, which is a different statement from "no direction".
     */
    private fun sessionDirectionAt(index: Int, candles: List<Candle>): Direction? {
        if (index !in candles.indices) return null
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = candles[index].timestamp
        val day = calendar.get(Calendar.DAY_OF_YEAR)
        val year = calendar.get(Calendar.YEAR)

        var start = index
        var bars = 0
        while (start > 0) {
            calendar.timeInMillis = candles[start - 1].timestamp
            if (calendar.get(Calendar.DAY_OF_YEAR) != day || calendar.get(Calendar.YEAR) != year) break
            start--
            bars++
        }
        if (bars < MIN_SESSION_BARS) return null

        val open = candles[start].open
        val close = candles[index].close
        // A session that has gone nowhere has no direction to align with, and
        // saying it is bullish because the close is one tick up would make the
        // filter meaningless rather than lenient.
        val span = candles.subList(start, index + 1)
        val range = span.maxOf { it.high } - span.minOf { it.low }
        if (range <= 0.0) return null
        val travelled = (close - open) / range
        return when {
            travelled >= MIN_SESSION_TRAVEL -> Direction.BULLISH
            travelled <= -MIN_SESSION_TRAVEL -> Direction.BEARISH
            else -> null
        }
    }

    private companion object {
        /** Bars beyond the swing window before a structure call means anything. */
        const val MIN_STRUCTURE_BARS = 6
        const val MIN_SESSION_BARS = 4

        /**
         * Share of the session's own range the close must sit away from the
         * open before the session counts as directional.
         */
        const val MIN_SESSION_TRAVEL = 0.2
    }
}
