package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.SmsAnalysis
import com.foxtrader.app.domain.model.SignalFusionResult
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
        litAnalysis: LitAnalysis? = null,
        smsAnalysis: SmsAnalysis? = null,
        latestConfirmedIndex: Int = candles.lastIndex,
        fusion: SignalFusionResult? = null,
    ): List<ChartSignal> {
        if (candles.isEmpty()) return emptyList()

        val signals = mutableListOf<ChartSignal>()

        // Strategy-library signals are already fully-formed ChartSignals with
        // correct bar indices and live/history flags, so they only need to join
        // the confluence pass alongside the engine-derived signals.
        signals += strategySignals

        // LIT X signal. Phase 13 plots it on the confirmation bar; legacy
        // signals without an explicit index safely fall back to the newest
        // confirmed bar rather than an in-progress candle.
        litXAnalysis?.signal?.let { signal ->
            val barIndex = signal.confirmationIndex.takeIf { it in candles.indices }
                ?: latestConfirmedIndex.takeIf { it in candles.indices }
                ?: candles.lastIndex
            signals.add(
                ChartSignal(
                    id = "litx_${signal.timestamp}",
                    source = SignalSource.LITX,
                    direction = signal.direction,
                    entry = signal.entry,
                    sl = signal.stopLoss,
                    tp = signal.takeProfit1,
                    barIndex = barIndex,
                    timestamp = signal.timestamp,
                    confidence = signal.confidence.score.toDouble(),
                    isLive = barIndex == latestConfirmedIndex,
                    label = buildSignalLabel("LiTX", signal.confidence.score, signal.confirmations),
                )
            )
        }

        // First-class LIT institutional sequence.
        litAnalysis?.signal?.let { signal ->
            val barIndex = signal.confirmationIndex.takeIf { it in candles.indices } ?: return@let
            signals.add(
                ChartSignal(
                    id = "lit_${signal.symbol}_${signal.timestamp}_${signal.direction}",
                    source = SignalSource.LIT,
                    direction = signal.direction,
                    entry = signal.entry,
                    sl = signal.stopLoss,
                    tp = signal.takeProfit,
                    barIndex = barIndex,
                    timestamp = signal.timestamp,
                    confidence = signal.confidence.toDouble(),
                    isLive = barIndex == latestConfirmedIndex,
                    label = buildSignalLabel("LiT", signal.confidence, signal.confirmations),
                )
            )
        }

        // Smart Money Structure: the marker belongs to the confirmation bar,
        // never the hindsight swing/event bar. SMS is context-only (no SL/TP).
        smsAnalysis?.signal?.let { signal ->
            val barIndex = signal.confirmationIndex.takeIf { it in candles.indices } ?: return@let
            val entry = candles[barIndex].close
            signals.add(
                ChartSignal(
                    id = "sms_${signal.symbol}_${signal.type}_${signal.eventIndex}_${signal.confirmationIndex}",
                    source = SignalSource.SMS,
                    direction = signal.direction,
                    entry = entry,
                    sl = 0.0,
                    tp = 0.0,
                    barIndex = barIndex,
                    timestamp = candles[barIndex].timestamp,
                    confidence = signal.confidence.toDouble(),
                    isLive = barIndex == latestConfirmedIndex,
                    label = buildSignalLabel("SMS ${signal.type}", signal.confidence, signal.confirmations),
                )
            )
        }

        // TradePro signal (only EXECUTE stage)
        tradeProAnalysis?.setup?.let { setup ->
            if (setup.stage == SetupStage.EXECUTE) {
                val barIndex = latestConfirmedIndex.takeIf { it in candles.indices } ?: candles.lastIndex
                signals.add(
                    ChartSignal(
                        id = "tradepro_${setup.symbol}_${setup.entry}_${candles[barIndex].timestamp}",
                        source = SignalSource.TRADEPRO,
                        direction = setup.direction,
                        entry = setup.entry,
                        sl = setup.stopLoss,
                        tp = setup.target1,
                        barIndex = barIndex,
                        // Stable/replayable timestamp: the confirmed bar that
                        // produced the setup, never wall-clock render time.
                        timestamp = candles[barIndex].timestamp,
                        confidence = setup.confidence.toDouble(),
                        isLive = latestConfirmedIndex in candles.indices,
                        label = buildSignalLabel(
                            "TradePro",
                            setup.confidence,
                            fusion?.confirmations ?: setup.confluences.takeLast(5),
                        ),
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
                    // Unified arrows are placed where the divergence becomes
                    // actionable. The dedicated SMT layer still draws the
                    // original swing-to-confirmation ray for context.
                    entry = confirmationCandle.close,
                    sl = 0.0,
                    tp = 0.0,
                    barIndex = div.confirmationIndex,
                    timestamp = confirmationCandle.timestamp,
                    confidence = div.confidence,
                    isLive = div.confirmationIndex == latestConfirmedIndex,
                    label = "SMT ${div.peerSymbol} · ${"%.0f".format(div.confidence)}",
                )
            )
        }

        // Treat every upstream engine as untrusted at this boundary. A malformed
        // NaN price, wrong-side stop, stale strategy index, or out-of-range SMT
        // marker must never reach Canvas math or confidence confluence.
        val renderable = signals
            .filter { it.isRenderable(candles) }
            .map { it.copy(confidence = normalizeConfidence(it.confidence)) }
        return applyConfluence(renderable, phase13TradeProAlreadyFused = fusion != null)
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
    private fun applyConfluence(
        signals: List<ChartSignal>,
        phase13TradeProAlreadyFused: Boolean,
    ): List<ChartSignal> {
        if (signals.size < 2) return signals

        // Only *live* signals represent the current read of the market, so only
        // they may vouch for one another. Historical markers (a strategy's past
        // setups, superseded SMT divergences) describe bars that have already
        // closed and must not inflate the confidence of a signal firing now.
        val liveSourcesByDirection: Map<Direction, Set<SignalSource>> =
            signals.filter { it.isLive && it.source != SignalSource.BINARY3M }
                .groupBy { it.direction }
                .mapValues { (_, group) -> group.map { it.source }.toSet() }

        return signals.map { signal ->
            if (!signal.isLive) return@map signal
            // Binary3m confidence is part of the fixed-expiry strategy contract
            // shared with the backtester. Do not mutate it with chart-only
            // confluence or the live display would diverge from measured logic.
            if (signal.source == SignalSource.BINARY3M) return@map signal
            // TradePro confidence has already been adjusted by the Phase 13
            // fusion engine. Applying the generic chart-source boost again
            // would double-count LiTX/LiT/SMS/SMT evidence.
            if (phase13TradeProAlreadyFused && signal.source == SignalSource.TRADEPRO) return@map signal
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
        if ((source == SignalSource.STRATEGY || source == SignalSource.BINARY3M) &&
            timestamp != candles[barIndex].timestamp
        ) return false

        if (source == SignalSource.SMT || source == SignalSource.SMS || source == SignalSource.BINARY3M) {
            return sl == 0.0 && tp == 0.0
        }

        if (!sl.isFinite() || !tp.isFinite() || sl <= 0.0 || tp <= 0.0) return false
        return when (direction) {
            Direction.BULLISH -> sl < entry && tp > entry
            Direction.BEARISH -> sl > entry && tp < entry
        }
    }

    private fun buildSignalLabel(name: String, score: Int, confirmations: List<String>): String {
        val compact = confirmations.take(4).joinToString(" · ")
        return if (compact.isBlank()) "$name $score" else "$name $score · $compact"
    }

    private companion object {
        /** Confidence added per additional distinct source confirming a direction. */
        const val CONFLUENCE_BOOST_PER_SOURCE = 0.04
        /** Maximum total confluence boost applied to any single signal. */
        const val CONFLUENCE_BOOST_MAX = 0.08
    }
}
