package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.SmsAnalysis

/**
 * Applies a computation made from the replay's revealed candle prefix while
 * retaining the full source series in [ChartUiState].
 *
 * The price canvas receives replay candles directly from ReplayState, whereas
 * this mapper replaces every indicator/structure layer with a prefix-only
 * computation. Keeping the full source series here lets Stop Replay restore the
 * normal chart without losing loaded history.
 */
internal fun ChartUiState.withReplayComputation(
    replayCandles: List<Candle>,
    computation: ChartComputation,
    toggles: IndicatorToggles,
    signals: List<ChartSignal>,
    litXAnalysis: LitXAnalysis? = null,
    litAnalysis: LitAnalysis? = null,
    smsAnalysis: SmsAnalysis? = null,
): ChartUiState {
    val sourceCandles = candles
    val sourceKind = dataSource
    return withComputation(
        candles = replayCandles,
        source = sourceKind,
        computation = computation,
        toggles = toggles,
        tradeProAnalysis = null,
        litXAnalysis = litXAnalysis,
        litAnalysis = litAnalysis,
        smsAnalysis = smsAnalysis,
        signalFusion = null,
        smtDivergences = emptyList(),
        barMode = ChartBarMode.TIME,
    ).copy(
        candles = sourceCandles,
        dataSource = sourceKind,
        signals = signals,
        // MTF/peer evidence is intentionally suppressed during local historical
        // replay unless it has been independently time-bounded to the replay bar.
        tradeProAnalysis = null,
        signalFusion = null,
        smtDivergences = emptyList(),
        aiDecision = null,
        confluence = null,
    )
}
