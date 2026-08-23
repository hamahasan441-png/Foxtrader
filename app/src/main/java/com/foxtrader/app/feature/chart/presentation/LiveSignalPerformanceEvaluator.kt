package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.signalintel.SignalOutcomeEvaluator
import kotlin.math.abs
import kotlin.math.max

/**
 * Forward-observed signal statistics shown on the live chart.
 *
 * The sample comes exclusively from [LiveSignalArchive.liveSnapshot], so a
 * retrospective strategy scan cannot manufacture past "live" wins. Standard
 * risk-bounded signals reuse [SignalOutcomeEvaluator]'s conservative rules:
 * evaluation starts after confirmation and an OHLC bar touching both TP and SL
 * counts as a loss because intrabar ordering is unknowable.
 *
 * Deriv BINARY3M uses its own fixed-expiry contract semantics:
 * signal on closed M1 bar i -> entry at OPEN(i+1) -> settle at CLOSE(i+3).
 * Context-only or malformed signals are reported as not evaluable rather than
 * being silently counted as wins/losses.
 */
data class LiveSignalPerformanceStats(
    val totalObserved: Int = 0,
    val evaluable: Int = 0,
    val decided: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    val unresolved: Int = 0,
    val notEvaluable: Int = 0,
    val winRatePercent: Double? = null,
)

class LiveSignalPerformanceEvaluator(
    private val riskEvaluator: SignalOutcomeEvaluator = SignalOutcomeEvaluator(),
    private val binaryExpiryBars: Int = DEFAULT_BINARY_EXPIRY_BARS,
) {
    fun evaluate(
        signals: List<ChartSignal>,
        candles: List<Candle>,
        timeframe: Timeframe,
    ): LiveSignalPerformanceStats {
        if (signals.isEmpty()) return LiveSignalPerformanceStats()

        val binarySignals = signals.filter { it.source == SignalSource.BINARY3M }
        val standardSignals = signals.filter { it.source != SignalSource.BINARY3M }
        val standardRecords = riskEvaluator.evaluate(standardSignals, candles)
        val standardEvaluatedIds = standardRecords.asSequence().map { it.signalId }.toHashSet()

        var wins = standardRecords.count { it.outcome == SignalOutcomeEvaluator.Outcome.WIN }
        var losses = standardRecords.count { it.outcome == SignalOutcomeEvaluator.Outcome.LOSS }
        var unresolved = standardRecords.count { it.outcome == SignalOutcomeEvaluator.Outcome.UNRESOLVED }
        var ties = 0
        var binaryEvaluable = 0
        var binaryNotEvaluable = 0

        binarySignals.forEach { signal ->
            when (evaluateBinary(signal, candles, timeframe)) {
                BinaryEvaluation.WIN -> {
                    binaryEvaluable++
                    wins++
                }
                BinaryEvaluation.LOSS -> {
                    binaryEvaluable++
                    losses++
                }
                BinaryEvaluation.TIE -> {
                    binaryEvaluable++
                    ties++
                }
                BinaryEvaluation.UNRESOLVED -> {
                    binaryEvaluable++
                    unresolved++
                }
                BinaryEvaluation.NOT_EVALUABLE -> binaryNotEvaluable++
            }
        }

        val standardNotEvaluable = standardSignals.count { it.id !in standardEvaluatedIds }
        val evaluable = standardRecords.size + binaryEvaluable
        val decided = wins + losses
        return LiveSignalPerformanceStats(
            totalObserved = signals.size,
            evaluable = evaluable,
            decided = decided,
            wins = wins,
            losses = losses,
            ties = ties,
            unresolved = unresolved,
            notEvaluable = standardNotEvaluable + binaryNotEvaluable,
            winRatePercent = decided.takeIf { it > 0 }
                ?.let { wins.toDouble() / it.toDouble() * 100.0 },
        )
    }

    private fun evaluateBinary(
        signal: ChartSignal,
        candles: List<Candle>,
        timeframe: Timeframe,
    ): BinaryEvaluation {
        if (timeframe != Timeframe.M1 || binaryExpiryBars < 1) return BinaryEvaluation.NOT_EVALUABLE
        if (signal.barIndex !in candles.indices) return BinaryEvaluation.NOT_EVALUABLE

        val entryIndex = signal.barIndex + 1
        val expiryIndex = signal.barIndex + binaryExpiryBars
        if (entryIndex !in candles.indices || expiryIndex !in candles.indices) {
            return BinaryEvaluation.UNRESOLVED
        }

        val entry = candles[entryIndex].open
        val expiry = candles[expiryIndex].close
        if (!entry.isFinite() || !expiry.isFinite() || entry <= 0.0 || expiry <= 0.0) {
            return BinaryEvaluation.NOT_EVALUABLE
        }

        val epsilon = max(abs(entry) * 1e-10, 1e-12)
        val delta = expiry - entry
        if (abs(delta) <= epsilon) return BinaryEvaluation.TIE
        return when (signal.direction) {
            Direction.BULLISH -> if (delta > 0.0) BinaryEvaluation.WIN else BinaryEvaluation.LOSS
            Direction.BEARISH -> if (delta < 0.0) BinaryEvaluation.WIN else BinaryEvaluation.LOSS
        }
    }

    private enum class BinaryEvaluation { WIN, LOSS, TIE, UNRESOLVED, NOT_EVALUABLE }

    private companion object {
        const val DEFAULT_BINARY_EXPIRY_BARS = 3
    }
}
