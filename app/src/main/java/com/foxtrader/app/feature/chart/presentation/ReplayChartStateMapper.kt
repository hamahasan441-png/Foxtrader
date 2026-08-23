package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.ChartSignal
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.SmsAnalysis
import kotlinx.collections.immutable.persistentListOf

/**
 * Clears computed market overlays before ReplayState exposes its first prefix.
 *
 * Replay recomputation runs off-main and may take a frame. Rendering the old
 * full-history OB/FVG/structure/indicator state during that gap would be a
 * transient future-data leak, so the chart briefly shows price-only history
 * until the first causal replay computation arrives.
 */
internal fun ChartUiState.withoutReplayDerivedState(): ChartUiState = copy(
    bias = Bias.NEUTRAL,
    structureBreaks = persistentListOf(),
    emaShort = null,
    emaLong = null,
    bollingerUpper = null,
    bollingerMiddle = null,
    bollingerLower = null,
    superTrendValues = null,
    superTrendDir = null,
    parabolicSar = null,
    vwap = null,
    anchoredVwap = null,
    anchoredVwapUpper = null,
    anchoredVwapLower = null,
    ichimokuTenkan = null,
    ichimokuKijun = null,
    ichimokuSenkouA = null,
    ichimokuSenkouB = null,
    ichimokuChikou = null,
    keltnerUpper = null,
    keltnerMiddle = null,
    keltnerLower = null,
    donchianUpper = null,
    donchianMiddle = null,
    donchianLower = null,
    pivotLevels = null,
    orderBlocks = persistentListOf(),
    fairValueGaps = persistentListOf(),
    liquidityPools = persistentListOf(),
    tradeProAnalysis = null,
    litXAnalysis = null,
    litAnalysis = null,
    smsAnalysis = null,
    signalFusion = null,
    smtDivergences = emptyList(),
    signals = emptyList(),
    volumeProfile = null,
    marketProfile = null,
    supportResistanceZones = persistentListOf(),
    autoFibLevels = persistentListOf(),
    autoFibDirection = null,
    autoFibSwingHigh = null,
    autoFibSwingLow = null,
    sessions = persistentListOf(),
    marketExplanation = null,
    confluence = null,
    aiDecision = null,
    rsiValues = null,
    macdLine = null,
    macdSignal = null,
    macdHistogram = null,
    stochasticK = null,
    stochasticD = null,
    obv = null,
    moneyFlowIndex = null,
)

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
    barMode: ChartBarMode,
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
        barMode = barMode,
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
