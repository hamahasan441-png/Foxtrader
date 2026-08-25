package com.foxtrader.app.domain.usecase.signalintel

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import javax.inject.Inject
import kotlin.math.abs

/**
 * Conservative Phase 13 signal-quality evaluator.
 *
 * It intentionally evaluates only objectively risk-bounded signals (positive
 * entry/SL/TP). Context-only SMS/SMT markers and fixed-expiry BINARY3M markers are excluded. Evaluation begins on
 * the bar *after* confirmation, which prevents same-bar hindsight. When both SL
 * and TP are touched by one candle the result is LOSS because intrabar ordering
 * is unknowable from OHLC alone. This makes reported accuracy deliberately
 * harder to inflate.
 */
class SignalOutcomeEvaluator @Inject constructor() {

    enum class Outcome { WIN, LOSS, UNRESOLVED }

    data class Record(
        val signalId: String,
        val source: SignalSource,
        val outcome: Outcome,
        val rMultiple: Double?,
        val barsHeld: Int,
        /** Rule set within [source]; see [ChartSignal.variant]. */
        val variant: String? = null,
    )

    /**
     * Outcome statistics for one rule set within one source — e.g. LITX /
     * SNIPER against LITX / PRECISION.
     *
     * This is the comparison that answers "is the selective mode actually
     * better", and it cannot be answered from [SourceStats] because every LiT
     * Adventure mode reports under the same [SignalSource]. Aggregating them
     * together averages a mode that fires constantly with one that fires rarely
     * and reports a number that describes neither.
     *
     * [stats] carries the same sample-size gate as [SourceStats], which matters
     * more here than anywhere else: partitioning by mode divides an already
     * small sample, so most variants will sit below the threshold for a long
     * time. That is the honest state of the evidence, not a bug to route around.
     */
    data class VariantStats(
        val source: SignalSource,
        val variant: String,
        val stats: SourceStats,
    )

    data class SourceStats(
        val source: SignalSource,
        val resolved: Int,
        val wins: Int,
        val losses: Int,
        val unresolved: Int,
        val winRate: Double?,
        val averageR: Double?,
        val profitFactor: Double?,
    ) {
        /**
         * Whether [resolved] is large enough for [winRate] to mean anything.
         *
         * Below [MIN_RESOLVED_FOR_RATE] the rate is dominated by noise: three
         * wins out of three is "100%", and a display that renders it as such is
         * actively misleading — the trader reads a number that has no
         * predictive content and sizes up on it. This flag exists so the UI can
         * show the raw counts and withhold the percentage, rather than the
         * evaluator silently returning null and losing the counts too.
         *
         * Selective modes (LiT Adventure SNIPER in particular) will sit below
         * this bar for a long time by design. That is the correct outcome: a
         * mode that fires rarely has to be judged over a longer horizon, not
         * awarded a confident number early.
         */
        val rateIsMeaningful: Boolean
            get() = resolved >= SignalOutcomeEvaluator.MIN_RESOLVED_FOR_RATE

        /** [winRate], but only once there is enough evidence to quote it. */
        val reportableWinRate: Double? get() = winRate?.takeIf { rateIsMeaningful }
    }

    fun evaluate(
        signals: List<ChartSignal>,
        candles: List<Candle>,
        maxHoldBars: Int = DEFAULT_MAX_HOLD_BARS,
    ): List<Record> {
        if (candles.isEmpty() || maxHoldBars <= 0) return emptyList()
        return signals
            .asSequence()
            .filter { it.source != SignalSource.SMS && it.source != SignalSource.SMT && it.source != SignalSource.BINARY3M }
            .filter { it.barIndex in candles.indices }
            .filter { validRiskGeometry(it) }
            .map { evaluateOne(it, candles, maxHoldBars) }
            .toList()
    }

    fun summarize(records: List<Record>): List<SourceStats> = records
        .groupBy { it.source }
        .map { (source, sourceRecords) ->
            val resolvedRecords = sourceRecords.filter { it.outcome != Outcome.UNRESOLVED }
            val wins = resolvedRecords.count { it.outcome == Outcome.WIN }
            val losses = resolvedRecords.count { it.outcome == Outcome.LOSS }
            val rs = resolvedRecords.mapNotNull { it.rMultiple }
            val positiveR = rs.filter { it > 0.0 }.sum()
            val negativeR = abs(rs.filter { it < 0.0 }.sum())
            SourceStats(
                source = source,
                resolved = resolvedRecords.size,
                wins = wins,
                losses = losses,
                unresolved = sourceRecords.size - resolvedRecords.size,
                winRate = resolvedRecords.takeIf { it.isNotEmpty() }
                    ?.let { wins.toDouble() / it.size.toDouble() },
                averageR = rs.takeIf { it.isNotEmpty() }?.average(),
                profitFactor = when {
                    positiveR <= 0.0 && negativeR <= 0.0 -> null
                    negativeR == 0.0 -> Double.POSITIVE_INFINITY
                    else -> positiveR / negativeR
                },
            )
        }
        .sortedBy { it.source.name }

    /**
     * Outcome statistics split by rule set.
     *
     * Records with no [Record.variant] are omitted rather than bucketed under a
     * placeholder: a source with a single rule set has nothing to compare
     * against, and inventing an "(unspecified)" row would imply a partition
     * that does not exist.
     */
    fun summarizeByVariant(records: List<Record>): List<VariantStats> = records
        .filter { it.variant != null }
        .groupBy { it.source to it.variant!! }
        .map { (key, group) ->
            VariantStats(
                source = key.first,
                variant = key.second,
                stats = summarize(group).single(),
            )
        }
        .sortedWith(compareBy({ it.source.name }, { it.variant }))

    private fun evaluateOne(signal: ChartSignal, candles: List<Candle>, maxHoldBars: Int): Record {
        val start = signal.barIndex + 1
        if (start !in candles.indices) {
            return Record(signal.id, signal.source, Outcome.UNRESOLVED, null, 0, signal.variant)
        }
        val end = minOf(candles.lastIndex, signal.barIndex + maxHoldBars)
        for (index in start..end) {
            val candle = candles[index]
            val stopTouched: Boolean
            val targetTouched: Boolean
            when (signal.direction) {
                Direction.BULLISH -> {
                    stopTouched = candle.low <= signal.sl
                    targetTouched = candle.high >= signal.tp
                }
                Direction.BEARISH -> {
                    stopTouched = candle.high >= signal.sl
                    targetTouched = candle.low <= signal.tp
                }
            }

            // Worst-case ordering when both levels are inside the same OHLC bar.
            if (stopTouched) {
                return Record(signal.id, signal.source, Outcome.LOSS, -1.0, index - signal.barIndex, signal.variant)
            }
            if (targetTouched) {
                val risk = abs(signal.entry - signal.sl)
                val reward = abs(signal.tp - signal.entry)
                val r = if (risk > 0.0) reward / risk else null
                return Record(signal.id, signal.source, Outcome.WIN, r, index - signal.barIndex, signal.variant)
            }
        }
        return Record(
            signal.id, signal.source, Outcome.UNRESOLVED, null,
            end - signal.barIndex, signal.variant,
        )
    }

    private fun validRiskGeometry(signal: ChartSignal): Boolean {
        if (!signal.entry.isFinite() || !signal.sl.isFinite() || !signal.tp.isFinite()) return false
        if (signal.entry <= 0.0 || signal.sl <= 0.0 || signal.tp <= 0.0) return false
        return when (signal.direction) {
            Direction.BULLISH -> signal.sl < signal.entry && signal.tp > signal.entry
            Direction.BEARISH -> signal.sl > signal.entry && signal.tp < signal.entry
        }
    }

    companion object {
        /**
         * Minimum resolved signals before a win rate is quotable.
         *
         * 20 is a judgement call, not a derived constant: at n=20 a true 50%
         * process still shows anywhere from roughly 30% to 70% at one standard
         * error, which is wide but no longer meaningless. Raising it makes the
         * display more honest and less useful early; lowering it does the
         * reverse. It is deliberately one named constant so the trade-off is
         * visible and adjustable in one place.
         */
        const val MIN_RESOLVED_FOR_RATE = 20

        const val DEFAULT_MAX_HOLD_BARS = 100
    }
}
