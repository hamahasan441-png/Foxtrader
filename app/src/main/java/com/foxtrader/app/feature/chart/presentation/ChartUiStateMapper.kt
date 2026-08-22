package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.ChartBarMode
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitAnalysis
import com.foxtrader.app.domain.model.SmsAnalysis
import com.foxtrader.app.domain.model.SignalFusionResult
import com.foxtrader.app.domain.model.tradepro.TradeProAnalysis
import com.foxtrader.app.domain.usecase.smt.SmtDivergenceDetector
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * Pure-ish mapping from a completed [ChartComputation] onto [ChartUiState].
 * The only presentation side effect is publishing the primary chart's bar-count
 * and bar-mode readiness snapshot for the indicator command center.
 */
internal fun ChartUiState.withComputation(
    candles: List<Candle>,
    source: CandleSource,
    computation: ChartComputation,
    toggles: IndicatorToggles,
    tradeProAnalysis: TradeProAnalysis?,
    litXAnalysis: LitXAnalysis? = null,
    litAnalysis: LitAnalysis? = null,
    smsAnalysis: SmsAnalysis? = null,
    signalFusion: SignalFusionResult? = null,
    smtDivergences: List<SmtDivergenceDetector.SmtDivergence> = emptyList(),
    barMode: ChartBarMode = ChartBarMode.TIME,
): ChartUiState {
    ChartIndicatorRuntime.publish(candles.size, barMode)

    // When the bar mode does not preserve the time axis (e.g. Renko), SMC
    // overlays that rely on accurate bar-index-to-time mapping are invalid.
    val gateSmcOverlays = !barMode.preservesTimeAxis

    return copy(
        candles = candles.asCandleSeries(),
        dataSource = source,
        bias = computation.bias,
        structureBreaks = when {
            gateSmcOverlays -> persistentListOf()
            toggles.structure -> computation.structureBreaks.toPersistentList()
            else -> persistentListOf()
        },
        emaShort = computation.overlays.emaShort.asImmutableDoubleSeries(),
        emaLong = computation.overlays.emaLong.asImmutableDoubleSeries(),
        bollingerUpper = computation.overlays.bollingerUpper.asImmutableDoubleSeries(),
        bollingerMiddle = computation.overlays.bollingerMiddle.asImmutableDoubleSeries(),
        bollingerLower = computation.overlays.bollingerLower.asImmutableDoubleSeries(),
        superTrendValues = computation.overlays.superTrendValues.asImmutableDoubleSeries(),
        superTrendDir = computation.overlays.superTrendDir.asImmutableIntSeries(),
        parabolicSar = computation.overlays.parabolicSar.asImmutableDoubleSeries(),
        vwap = computation.overlays.vwap.asImmutableDoubleSeries(),
        anchoredVwap = computation.overlays.anchoredVwap?.vwap.asImmutableDoubleSeries(),
        anchoredVwapUpper = computation.overlays.anchoredVwap?.upperBand.asImmutableDoubleSeries(),
        anchoredVwapLower = computation.overlays.anchoredVwap?.lowerBand.asImmutableDoubleSeries(),
        ichimokuTenkan = computation.overlays.ichimokuTenkan.asImmutableDoubleSeries(),
        ichimokuKijun = computation.overlays.ichimokuKijun.asImmutableDoubleSeries(),
        ichimokuSenkouA = computation.overlays.ichimokuSenkouA.asImmutableDoubleSeries(),
        ichimokuSenkouB = computation.overlays.ichimokuSenkouB.asImmutableDoubleSeries(),
        ichimokuChikou = computation.overlays.ichimokuChikou.asImmutableDoubleSeries(),
        rsiValues = computation.overlays.rsi.asImmutableDoubleSeries(),
        macdLine = computation.overlays.macdLine.asImmutableDoubleSeries(),
        macdSignal = computation.overlays.macdSignal.asImmutableDoubleSeries(),
        macdHistogram = computation.overlays.macdHistogram.asImmutableDoubleSeries(),
        stochasticK = computation.overlays.stochasticK.asImmutableDoubleSeries(),
        stochasticD = computation.overlays.stochasticD.asImmutableDoubleSeries(),
        obv = computation.overlays.obv.asImmutableDoubleSeries(),
        moneyFlowIndex = computation.overlays.moneyFlowIndex.asImmutableDoubleSeries(),
        keltnerUpper = computation.overlays.keltnerUpper.asImmutableDoubleSeries(),
        keltnerMiddle = computation.overlays.keltnerMiddle.asImmutableDoubleSeries(),
        keltnerLower = computation.overlays.keltnerLower.asImmutableDoubleSeries(),
        donchianUpper = computation.overlays.donchianUpper.asImmutableDoubleSeries(),
        donchianMiddle = computation.overlays.donchianMiddle.asImmutableDoubleSeries(),
        donchianLower = computation.overlays.donchianLower.asImmutableDoubleSeries(),
        pivotLevels = if (gateSmcOverlays) null else computation.overlays.pivotLevels,
        orderBlocks = if (gateSmcOverlays) persistentListOf() else computation.overlays.orderBlocks.toPersistentList(),
        fairValueGaps = if (gateSmcOverlays) persistentListOf() else computation.overlays.fairValueGaps.toPersistentList(),
        liquidityPools = if (gateSmcOverlays) persistentListOf() else computation.overlays.liquidityPools.toPersistentList(),
        tradeProAnalysis = tradeProAnalysis,
        litXAnalysis = litXAnalysis,
        litAnalysis = litAnalysis,
        smsAnalysis = smsAnalysis,
        signalFusion = signalFusion,
        smtDivergences = smtDivergences,
        volumeProfile = computation.overlays.volumeProfile,
        marketProfile = computation.overlays.marketProfile,
        supportResistanceZones = computation.overlays.supportResistanceZones.toPersistentList(),
        autoFibLevels = computation.overlays.autoFibLevels.toPersistentList(),
        autoFibDirection = computation.overlays.autoFibDirection,
        autoFibSwingHigh = computation.overlays.autoFibSwingHigh,
        autoFibSwingLow = computation.overlays.autoFibSwingLow,
        sessions = computation.overlays.sessions.toPersistentList(),
        marketExplanation = computation.marketExplanation,
        confluence = if (toggles.confluence) confluence else null,
        isLoading = candles.isEmpty() && error == null,
    )
}
