package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.ParabolicSar
import com.foxtrader.app.domain.usecase.indicators.SuperTrend
import com.foxtrader.app.domain.usecase.indicators.TechnicalIndicators
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import javax.inject.Inject
import kotlin.math.abs

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
    private val marketProfile: MarketProfile,
    private val supportResistanceDetector: SupportResistanceDetector,
    private val fibonacciEngine: FibonacciEngine,
) {

    /**
     * Holds all computed overlay data for a single chart render frame.
     *
     * NOTE on equality: this data class contains primitive arrays which do not
     * override equals/hashCode by identity in Kotlin. We provide explicit
     * overrides so that two Result instances with identical array *contents*
     * compare equal — required for correct behaviour in tests and any caching
     * layers.
     */
    data class Result(
        val emaShort: DoubleArray?,
        val emaLong: DoubleArray?,
        val bollingerUpper: DoubleArray?,
        val bollingerMiddle: DoubleArray?,
        val bollingerLower: DoubleArray?,
        val superTrendValues: DoubleArray?,
        val superTrendDir: IntArray?,
        val superTrendFinalUpper: DoubleArray?,
        val superTrendFinalLower: DoubleArray?,
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
        val marketProfile: MarketProfile.ProfileResult?,
        val supportResistanceZones: List<SupportResistanceDetector.SRZone>,
        val autoFibLevels: List<FibonacciEngine.FibLevel>,
        val autoFibDirection: Direction?,
        val autoFibSwingHigh: Double?,
        val autoFibSwingLow: Double?,
        val sessions: List<com.foxtrader.app.domain.model.SessionRange>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return emaShort.contentEquals(other.emaShort) &&
                emaLong.contentEquals(other.emaLong) &&
                bollingerUpper.contentEquals(other.bollingerUpper) &&
                bollingerMiddle.contentEquals(other.bollingerMiddle) &&
                bollingerLower.contentEquals(other.bollingerLower) &&
                superTrendValues.contentEquals(other.superTrendValues) &&
                superTrendDir.contentEquals(other.superTrendDir) &&
                superTrendFinalUpper.contentEquals(other.superTrendFinalUpper) &&
                superTrendFinalLower.contentEquals(other.superTrendFinalLower) &&
                parabolicSar.contentEquals(other.parabolicSar) &&
                vwap.contentEquals(other.vwap) &&
                ichimokuTenkan.contentEquals(other.ichimokuTenkan) &&
                ichimokuKijun.contentEquals(other.ichimokuKijun) &&
                ichimokuSenkouA.contentEquals(other.ichimokuSenkouA) &&
                ichimokuSenkouB.contentEquals(other.ichimokuSenkouB) &&
                ichimokuChikou.contentEquals(other.ichimokuChikou) &&
                orderBlocks == other.orderBlocks &&
                fairValueGaps == other.fairValueGaps &&
                liquidityPools == other.liquidityPools &&
                volumeProfile == other.volumeProfile &&
                marketProfile == other.marketProfile &&
                supportResistanceZones == other.supportResistanceZones &&
                autoFibLevels == other.autoFibLevels &&
                autoFibDirection == other.autoFibDirection &&
                autoFibSwingHigh == other.autoFibSwingHigh &&
                autoFibSwingLow == other.autoFibSwingLow &&
                sessions == other.sessions
        }

        override fun hashCode(): Int {
            var h = emaShort.contentHashCode()
            h = 31 * h + emaLong.contentHashCode()
            h = 31 * h + bollingerUpper.contentHashCode()
            h = 31 * h + bollingerMiddle.contentHashCode()
            h = 31 * h + bollingerLower.contentHashCode()
            h = 31 * h + superTrendValues.contentHashCode()
            h = 31 * h + superTrendDir.contentHashCode()
            h = 31 * h + superTrendFinalUpper.contentHashCode()
            h = 31 * h + superTrendFinalLower.contentHashCode()
            h = 31 * h + parabolicSar.contentHashCode()
            h = 31 * h + vwap.contentHashCode()
            h = 31 * h + ichimokuTenkan.contentHashCode()
            h = 31 * h + ichimokuKijun.contentHashCode()
            h = 31 * h + ichimokuSenkouA.contentHashCode()
            h = 31 * h + ichimokuSenkouB.contentHashCode()
            h = 31 * h + ichimokuChikou.contentHashCode()
            h = 31 * h + orderBlocks.hashCode()
            h = 31 * h + fairValueGaps.hashCode()
            h = 31 * h + liquidityPools.hashCode()
            h = 31 * h + (volumeProfile?.hashCode() ?: 0)
            h = 31 * h + (marketProfile?.hashCode() ?: 0)
            h = 31 * h + supportResistanceZones.hashCode()
            h = 31 * h + autoFibLevels.hashCode()
            h = 31 * h + (autoFibDirection?.hashCode() ?: 0)
            h = 31 * h + (autoFibSwingHigh?.hashCode() ?: 0)
            h = 31 * h + (autoFibSwingLow?.hashCode() ?: 0)
            h = 31 * h + sessions.hashCode()
            return h
        }
    }

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
        val marketProfileResult = if (toggles.marketProfile && candles.size >= 30)
            marketProfile.compute(candles) else null
        val supportResistanceZones = if (toggles.supportResistance && candles.size >= 25)
            supportResistanceDetector.detect(candles) else emptyList()
        val autoFib = if (toggles.fibonacci && candles.size >= AUTO_FIB_MIN_BARS)
            buildAutoFib(candles) else null
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
            superTrendFinalUpper = st?.finalUpperBands,
            superTrendFinalLower = st?.finalLowerBands,
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
            marketProfile = marketProfileResult,
            supportResistanceZones = supportResistanceZones,
            autoFibLevels = autoFib?.levels.orEmpty(),
            autoFibDirection = autoFib?.direction,
            autoFibSwingHigh = autoFib?.swingHigh,
            autoFibSwingLow = autoFib?.swingLow,
            sessions = sessions,
        )
    }

    fun computeIncrementalVisuals(
        candles: List<Candle>,
        toggles: IndicatorToggles,
        previous: Result,
        recomputeFrom: Int,
    ): Result {
        val emaShort = if (toggles.ema && candles.size >= 20)
            TechnicalIndicators.calculateEMAIncremental(candles, 20, previous.emaShort, recomputeFrom) else null
        val emaLong = if (toggles.ema && candles.size >= 50)
            TechnicalIndicators.calculateEMAIncremental(candles, 50, previous.emaLong, recomputeFrom) else null
        val vwap = if (toggles.vwap && candles.isNotEmpty())
            TechnicalIndicators.calculateVWAPIncremental(candles, previous.vwap, recomputeFrom) else null
        val ichimoku = if (toggles.ichimoku && candles.size >= 52)
            ichimokuCloud.calculateIncremental(candles, previous.toIchimokuResult(), recomputeFrom) else null
        val boll = if (toggles.bollinger && candles.size >= 20)
            bollingerBands.calculateIncremental(candles, previous.toBollingerResult(), recomputeFrom) else null
        val st = if (toggles.superTrend && candles.size >= 15)
            superTrend.calculateIncremental(candles, previous.toSuperTrendResult(), recomputeFrom) else null
        val psar = if (toggles.parabolicSar && candles.size >= 2)
            parabolicSar.calculate(candles).sar else null

        return previous.copy(
            emaShort = emaShort,
            emaLong = emaLong,
            bollingerUpper = boll?.upper,
            bollingerMiddle = boll?.middle,
            bollingerLower = boll?.lower,
            superTrendValues = st?.values,
            superTrendDir = st?.direction,
            superTrendFinalUpper = st?.finalUpperBands,
            superTrendFinalLower = st?.finalLowerBands,
            parabolicSar = psar,
            vwap = vwap,
            ichimokuTenkan = ichimoku?.tenkan,
            ichimokuKijun = ichimoku?.kijun,
            ichimokuSenkouA = ichimoku?.senkouA,
            ichimokuSenkouB = ichimoku?.senkouB,
            ichimokuChikou = ichimoku?.chikou,
        )
    }

    private fun Result.toBollingerResult(): BollingerBands.BollingerResult? {
        val upper = bollingerUpper ?: return null
        val middle = bollingerMiddle ?: return null
        val lower = bollingerLower ?: return null
        val percentB = DoubleArray(upper.size)
        val bandwidth = DoubleArray(upper.size)
        for (i in upper.indices) {
            val range = (upper[i] - lower[i]).coerceAtLeast(1e-9)
            percentB[i] = (middle[i] - lower[i]) / range
            bandwidth[i] = if (middle[i] != 0.0) (upper[i] - lower[i]) / middle[i] else 0.0
        }
        return BollingerBands.BollingerResult(middle, upper, lower, percentB, bandwidth)
    }

    private fun Result.toIchimokuResult(): IchimokuCloud.IchimokuResult? {
        val tenkan = ichimokuTenkan ?: return null
        val kijun = ichimokuKijun ?: return null
        val senkouA = ichimokuSenkouA ?: return null
        val senkouB = ichimokuSenkouB ?: return null
        val chikou = ichimokuChikou ?: return null
        return IchimokuCloud.IchimokuResult(tenkan, kijun, senkouA, senkouB, chikou, displacement = 26)
    }

    private fun Result.toSuperTrendResult(): SuperTrend.SuperTrendResult? {
        val values = superTrendValues ?: return null
        val direction = superTrendDir ?: return null
        val finalUpper = superTrendFinalUpper ?: return null
        val finalLower = superTrendFinalLower ?: return null
        return SuperTrend.SuperTrendResult(
            values = values,
            direction = direction,
            finalUpperBands = finalUpper,
            finalLowerBands = finalLower,
        )
    }

    private fun buildAutoFib(candles: List<Candle>): AutoFibResult? {
        val lookback = candles.takeLast(minOf(candles.size, AUTO_FIB_LOOKBACK))
        if (lookback.size < AUTO_FIB_MIN_BARS) return null

        val highest = lookback.withIndex().maxByOrNull { it.value.high } ?: return null
        val lowest = lookback.withIndex().minByOrNull { it.value.low } ?: return null
        if (highest.index == lowest.index) return null
        if (abs(highest.index - lowest.index) < AUTO_FIB_MIN_SWING_BARS) return null

        val swingHigh = highest.value.high
        val swingLow = lowest.value.low
        if (swingHigh - swingLow <= 1e-9) return null

        val direction = if (highest.index > lowest.index) Direction.BULLISH else Direction.BEARISH
        return AutoFibResult(
            levels = fibonacciEngine.retracements(
                swingHigh = swingHigh,
                swingLow = swingLow,
                direction = direction,
            ),
            direction = direction,
            swingHigh = swingHigh,
            swingLow = swingLow,
        )
    }

    private data class AutoFibResult(
        val levels: List<FibonacciEngine.FibLevel>,
        val direction: Direction,
        val swingHigh: Double,
        val swingLow: Double,
    )

    private companion object {
        const val AUTO_FIB_LOOKBACK = 120
        const val AUTO_FIB_MIN_BARS = 30
        const val AUTO_FIB_MIN_SWING_BARS = 6
    }
}
