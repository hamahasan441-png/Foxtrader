package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.ParabolicSar
import com.foxtrader.app.domain.usecase.indicators.SuperTrend
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import javax.inject.Inject

/**
 * Domain use case that computes all chart overlays (indicators + SMC + sessions)
 * from a candle series and a set of toggle flags.
 *
 * Extracted from [com.foxtrader.app.feature.chart.presentation.ChartViewModel]
 * to keep the ViewModel thin and to make indicator computation independently
 * testable without a ViewModel / CoroutineScope.
 *
 * All computations are pure: same inputs → same outputs. The use case carries
 * no mutable state and is safe to call from any coroutine context.
 */
class ComputeIndicatorsUseCase @Inject constructor(
    private val bollingerBands: BollingerBands,
    private val ichimokuCloud: IchimokuCloud,
    private val superTrend: SuperTrend,
    private val parabolicSar: ParabolicSar,
    private val smcDetector: SmcDetector,
    private val sessionDetector: SessionDetector,
) {

    /** Holds all computed overlay data for a single chart render frame. */
    data class Result(
        val emaShort: DoubleArray?,
        val emaLong: DoubleArray?,
        val bollingerUpper: DoubleArray?,
        val bollingerMiddle: DoubleArray?,
        val bollingerLower: DoubleArray?,
        val superTrendValues: DoubleArray?,
        val superTrendDir: IntArray?,
        val parabolicSar: DoubleArray?,
        val vwap: DoubleArray?,
        val ichimokuTenkan: DoubleArray?,
        val ichimokuKijun: DoubleArray?,
        val ichimokuSenkouA: DoubleArray?,
        val ichimokuSenkouB: DoubleArray?,
        val ichimokuChikou: DoubleArray?,
        val orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock>,
        val fairValueGaps: List<com.foxtrader.app.domain.model.FairValueGap>,
        val liquidityPools: List<com.foxtrader.app.domain.model.LiquidityPool>,
        val volumeProfile: com.foxtrader.app.domain.model.VolumeProfile?,
        val sessions: List<com.foxtrader.app.domain.model.SessionRange>,
    )

    /**
     * Compute all enabled indicators for [candles] according to [toggles].
     * Safe to call on Dispatchers.Default (CPU-bound, no I/O).
     */
    operator fun invoke(candles: List<Candle>, toggles: IndicatorToggles): Result {
        val emaShort = if (toggles.ema && candles.size >= 20)
            TechnicalIndicators.calculateEMA(candles, 20) else null
        val emaLong = if (toggles.ema && candles.size >= 50)
            TechnicalIndicators.calculateEMA(candles, 50) else null
        val vwap = if (toggles.vwap && candles.isNotEmpty())
            TechnicalIndicators.calculateVWAP(candles) else null

        val ichimoku = if (toggles.ichimoku && candles.size >= 52)
            ichimokuCloud.calculate(candles) else null
        val boll = if (toggles.bollinger && candles.size >= 20)
            bollingerBands.calculate(candles) else null
        val st = if (toggles.superTrend && candles.size >= 15)
            superTrend.calculate(candles) else null
        val psar = if (toggles.parabolicSar && candles.size >= 2)
            parabolicSar.calculate(candles).sar else null

        val orderBlocks = if (toggles.orderBlocks)
            smcDetector.detectOrderBlocks(candles) else emptyList()
        val fairValueGaps = if (toggles.fairValueGaps)
            smcDetector.detectFairValueGaps(candles) else emptyList()
        val liquidityPools = if (toggles.liquidity)
            smcDetector.detectLiquidity(candles) else emptyList()
        val volumeProfile = if (toggles.volumeProfile && candles.size >= 20)
            smcDetector.computeVolumeProfile(candles) else null
        val sessions = if (toggles.sessions)
            sessionDetector.detectSessions(candles) else emptyList()

        return Result(
            emaShort = emaShort,
            emaLong = emaLong,
            bollingerUpper = boll?.upper,
            bollingerMiddle = boll?.middle,
            bollingerLower = boll?.lower,
            superTrendValues = st?.values,
            superTrendDir = st?.direction,
            parabolicSar = psar,
            vwap = vwap,
            ichimokuTenkan = ichimoku?.tenkan,
            ichimokuKijun = ichimoku?.kijun,
            ichimokuSenkouA = ichimoku?.senkouA,
            ichimokuSenkouB = ichimoku?.senkouB,
            ichimokuChikou = ichimoku?.chikou,
            orderBlocks = orderBlocks,
            fairValueGaps = fairValueGaps,
            liquidityPools = liquidityPools,
            volumeProfile = volumeProfile,
            sessions = sessions,
        )
    }
}
