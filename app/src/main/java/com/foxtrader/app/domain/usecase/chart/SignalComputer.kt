package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.tradepro.SetupStage
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import javax.inject.Inject

/**
 * Builds a unified [ChartSignal] list from the current analysis results
 * produced by LIT X, TradePro, and the SMT divergence detector.
 *
 * A signal is live only when its setup/confirmation belongs to the current
 * chart bar; historical entries stay historical even if they are the newest
 * signal available from that source.
 *
 * This class is extracted from ChartViewModel for testability: it is a pure
 * function with no side-effects or framework dependencies.
 */
class SignalComputer @Inject constructor() {

    /**
     * Compute a unified signal list from the three analysis pipelines.
     *
     * @param litXAnalysis the current LIT X analysis result (nullable if LIT X is disabled)
     * @param tradeProAnalysis the current TradePro analysis result (nullable if none)
     * @param smtDivergences the list of detected SMT divergences (may be empty)
     * @param candles the current display candles (used for bar-index reference)
     * @param currentTimeMillis the current timestamp supplier (defaults to system clock)
     * @return a list of [ChartSignal] ordered by source priority
     */
    fun computeSignals(
        litXAnalysis: LitXAnalysis?,
        tradeProAnalysis: TradeProAnalysis?,
        smtDivergences: List<SmtDivergenceDetector.SmtDivergence>,
        candles: List<Candle>,
        currentTimeMillis: Long = System.currentTimeMillis(),
        strategySignals: List<ChartSignal> = emptyList(),
    ): List<ChartSignal> {
        if (candles.isEmpty()) return emptyList()

        val signals = mutableListOf<ChartSignal>()

        // Strategy-library signals are already fully-formed ChartSignals with
        // correct bar indices and live/history flags, so they only need to join
        // the confluence pass alongside the engine-derived signals.
        signals += strategySignals

        // LIT X signal
        litXAnalysis?.signal?.let { signal ->
            signals.add(
                ChartSignal(
                    id = "litx_${signal.timestamp}",
                    source = SignalSource.LITX,
                    direction = signal.direction,
                    entry = signal.entry,
                    sl = signal.stopLoss,
                    tp = signal.takeProfit1,
                    barIndex = candles.lastIndex,
                    timestamp = signal.timestamp,
                    confidence = signal.confidence.score.toDouble(),
                    isLive = true,
                )
            )
        }

        // TradePro signal (only EXECUTE stage)
        tradeProAnalysis?.setup?.let { setup ->
            if (setup.stage == SetupStage.EXECUTE) {
                signals.add(
                    ChartSignal(
                        id = "tradepro_${setup.symbol}_${setup.entry}",
                        source = SignalSource.TRADEPRO,
                        direction = setup.direction,
                        entry = setup.entry,
                        sl = setup.stopLoss,
                        tp = setup.target1,
                        barIndex = candles.lastIndex,
                        timestamp = currentTimeMillis,
                        confidence = setup.confidence.toDouble(),
                        isLive = true,
                    )
                )
            }
        }

        // SMT divergences
        for (div in smtDivergences) {
            val confirmationCandle = candles.getOrNull(div.confirmationIndex) ?: continue
            if (div.primaryIndex !in candles.indices || div.confirmationIndex < div.primaryIndex) continue
            signals.add(
                ChartSignal(
                    id = "smt_${div.primarySymbol}_${div.peerSymbol}_${div.type.name}_" +
                        "${div.primaryIndex}_${div.confirmationIndex}",
                    source = SignalSource.SMT,
                    direction = div.direction,
                    entry = div.primaryPrice,
                    sl = 0.0,
                    tp = 0.0,
                    barIndex = div.primaryIndex,
                    // The divergence is plotted at the swing, but it only
                    // becomes knowable after the right-hand confirmation bars.
                    timestamp = confirmationCandle.timestamp,
                    confidence = div.confidence,
                    isLive = div.confirmationIndex == candles.lastIndex,
                )
            )
        }

        // Treat every upstream engine as untrusted at this boundary. A malformed
        // NaN price, wrong-side stop, stale strategy index, or out-of-range SMT
        // marker must never reach Canvas math or confidence confluence.
        val renderable = signals
            .filter { it.isRenderable(candles) }
            .map { it.copy(confidence = normalizeConfidence(it.confidence)) }
        return applyConfluence(renderable)
    }

    /**
     * Reinforce confidence when independent methodologies agree.
     *
     * LIT X, TradePro and SMT are derived from different logic, so when two or
     * more of them point the same direction the combined signal is empirically
     * stronger than any one alone. Each signal gets a bounded boost of
     * [CONFLUENCE_BOOST_PER_SOURCE] per *other distinct source* confirming its
     * direction, capped at [CONFLUENCE_BOOST_MAX] and never exceeding 1.0.
     *
     * A single source (or multiple entries from the same source, e.g. several
     * SMT divergences) receives no boost, so single-source output is unchanged.
     */
    private fun applyConfluence(signals: List<ChartSignal>): List<ChartSignal> {
        if (signals.size < 2) return signals

        // Only *live* signals represent the current read of the market, so only
        // they may vouch for one another. Historical markers (a strategy's past
        // setups, superseded SMT divergences) describe bars that have already
        // closed and must not inflate the confidence of a signal firing now.
        val liveSourcesByDirection: Map<Direction, Set<SignalSource>> =
            signals.filter { it.isLive }
                .groupBy { it.direction }
                .mapValues { (_, group) -> group.map { it.source }.toSet() }

        return signals.map { signal ->
            if (!signal.isLive) return@map signal
            val agreeing = liveSourcesByDirection[signal.direction].orEmpty()
            val otherSources = (agreeing - signal.source).size
            if (otherSources <= 0) {
                signal
            } else {
                val boost = (otherSources * CONFLUENCE_BOOST_PER_SOURCE)
                    .coerceAtMost(CONFLUENCE_BOOST_MAX)
                signal.copy(confidence = (signal.confidence + boost).coerceAtMost(1.0))
            }
        }
    }

    /**
     * Domain engines historically used both 0..1 and 0..100 confidence scales.
     * Normalise at the chart boundary so no signal can render as 6,200% or
     * bypass the confluence cap.
     */
    private fun normalizeConfidence(value: Double): Double = when {
        !value.isFinite() -> 0.0
        value > 1.0 -> value / 100.0
        else -> value
    }.coerceIn(0.0, 1.0)

    private fun ChartSignal.isRenderable(candles: List<Candle>): Boolean {
        if (barIndex !in candles.indices || !entry.isFinite() || entry <= 0.0) return false
        if (!confidence.isFinite()) return false
        if (source == SignalSource.STRATEGY && timestamp != candles[barIndex].timestamp) return false

        if (source == SignalSource.SMT) {
            return sl == 0.0 && tp == 0.0
        }

        if (!sl.isFinite() || !tp.isFinite() || sl <= 0.0 || tp <= 0.0) return false
        return when (direction) {
            Direction.BULLISH -> sl < entry && tp > entry
            Direction.BEARISH -> sl > entry && tp < entry
        }
    }

    private companion object {
        /** Confidence added per additional distinct source confirming a direction. */
        const val CONFLUENCE_BOOST_PER_SOURCE = 0.04
        /** Maximum total confluence boost applied to any single signal. */
        const val CONFLUENCE_BOOST_MAX = 0.08
    }
}
