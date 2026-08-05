package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
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
 * The most recent signal of each source is marked [ChartSignal.isLive] = true;
 * older entries are marked false so the chart layer can render them with
 * different opacities (live = 0.9, history = 0.3).
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
    ): List<ChartSignal> {
        if (candles.isEmpty()) return emptyList()

        val signals = mutableListOf<ChartSignal>()

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
                    confidence = signal.confidence.score / 100.0,
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
                        confidence = setup.confidence / 100.0,
                        isLive = true,
                    )
                )
            }
        }

        // SMT divergences
        val lastSmtDiv = smtDivergences.lastOrNull()
        for (div in smtDivergences) {
            signals.add(
                ChartSignal(
                    id = "smt_${div.primarySymbol}_${div.primaryIndex}",
                    source = SignalSource.SMT,
                    direction = div.direction,
                    entry = div.primaryPrice,
                    sl = 0.0,
                    tp = 0.0,
                    barIndex = div.primaryIndex,
                    timestamp = currentTimeMillis,
                    confidence = div.confidence,
                    isLive = div == lastSmtDiv,
                )
            )
        }

        return signals
    }
}
