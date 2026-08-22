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
    )

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

    private fun evaluateOne(signal: ChartSignal, candles: List<Candle>, maxHoldBars: Int): Record {
        val start = signal.barIndex + 1
        if (start !in candles.indices) {
            return Record(signal.id, signal.source, Outcome.UNRESOLVED, null, 0)
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
                return Record(signal.id, signal.source, Outcome.LOSS, -1.0, index - signal.barIndex)
            }
            if (targetTouched) {
                val risk = abs(signal.entry - signal.sl)
                val reward = abs(signal.tp - signal.entry)
                val r = if (risk > 0.0) reward / risk else null
                return Record(signal.id, signal.source, Outcome.WIN, r, index - signal.barIndex)
            }
        }
        return Record(signal.id, signal.source, Outcome.UNRESOLVED, null, end - signal.barIndex)
    }

    private fun validRiskGeometry(signal: ChartSignal): Boolean {
        if (!signal.entry.isFinite() || !signal.sl.isFinite() || !signal.tp.isFinite()) return false
        if (signal.entry <= 0.0 || signal.sl <= 0.0 || signal.tp <= 0.0) return false
        return when (signal.direction) {
            Direction.BULLISH -> signal.sl < signal.entry && signal.tp > signal.entry
            Direction.BEARISH -> signal.sl > signal.entry && signal.tp < signal.entry
        }
    }

    private companion object {
        const val DEFAULT_MAX_HOLD_BARS = 100
    }
}
