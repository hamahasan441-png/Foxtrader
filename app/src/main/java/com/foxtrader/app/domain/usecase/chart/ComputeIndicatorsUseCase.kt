package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.ChannelIndicators
import com.foxtrader.app.domain.usecase.indicators.PivotPoints
import com.foxtrader.app.domain.usecase.indicators.StochasticOscillator
import com.foxtrader.app.domain.usecase.indicators.VolumeIndicators
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.ParabolicSar
import com.foxtrader.app.domain.usecase.indicators.AnchoredVwap
import com.foxtrader.app.domain.usecase.indicators.AnchoredVwapResult
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
    private val channelIndicators: ChannelIndicators,
    private val stochasticOscillator: StochasticOscillator,
    private val volumeIndicators: VolumeIndicators,
    private val pivotPoints: PivotPoints,
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
        val anchoredVwap: AnchoredVwapResult?,
        val ichimokuTenkan: DoubleArray?,
        val ichimokuKijun: DoubleArray?,
        val ichimokuSenkouA: DoubleArray?,
        val ichimokuSenkouB: DoubleArray?,
        val ichimokuChikou: DoubleArray?,
        val rsi: DoubleArray?,
        val macdLine: DoubleArray?,
        val macdSignal: DoubleArray?,
        val macdHistogram: DoubleArray?,
        val stochasticK: DoubleArray?,
        val stochasticD: DoubleArray?,
        val obv: DoubleArray?,
        val moneyFlowIndex: DoubleArray?,
        val keltnerUpper: DoubleArray?,
        val keltnerMiddle: DoubleArray?,
        val keltnerLower: DoubleArray?,
        val donchianUpper: DoubleArray?,
        val donchianMiddle: DoubleArray?,
        val donchianLower: DoubleArray?,
        val pivotLevels: PivotPoints.PivotLevels?,
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
                anchoredVwap == other.anchoredVwap &&
                ichimokuTenkan.contentEquals(other.ichimokuTenkan) &&
                ichimokuKijun.contentEquals(other.ichimokuKijun) &&
                ichimokuSenkouA.contentEquals(other.ichimokuSenkouA) &&
                ichimokuSenkouB.contentEquals(other.ichimokuSenkouB) &&
                ichimokuChikou.contentEquals(other.ichimokuChikou) &&
                rsi.contentEquals(other.rsi) &&
                macdLine.contentEquals(other.macdLine) &&
                macdSignal.contentEquals(other.macdSignal) &&
                macdHistogram.contentEquals(other.macdHistogram) &&
                stochasticK.contentEquals(other.stochasticK) &&
                stochasticD.contentEquals(other.stochasticD) &&
                obv.contentEquals(other.obv) &&
                moneyFlowIndex.contentEquals(other.moneyFlowIndex) &&
                keltnerUpper.contentEquals(other.keltnerUpper) &&
                keltnerMiddle.contentEquals(other.keltnerMiddle) &&
                keltnerLower.contentEquals(other.keltnerLower) &&
                donchianUpper.contentEquals(other.donchianUpper) &&
                donchianMiddle.contentEquals(other.donchianMiddle) &&
                donchianLower.contentEquals(other.donchianLower) &&
                pivotLevels == other.pivotLevels &&
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
            h = 31 * h + (anchoredVwap?.hashCode() ?: 0)
            h = 31 * h + ichimokuTenkan.contentHashCode()
            h = 31 * h + ichimokuKijun.contentHashCode()
            h = 31 * h + ichimokuSenkouA.contentHashCode()
            h = 31 * h + ichimokuSenkouB.contentHashCode()
            h = 31 * h + ichimokuChikou.contentHashCode()
            h = 31 * h + rsi.contentHashCode()
            h = 31 * h + macdLine.contentHashCode()
            h = 31 * h + macdSignal.contentHashCode()
            h = 31 * h + macdHistogram.contentHashCode()
            h = 31 * h + stochasticK.contentHashCode()
            h = 31 * h + stochasticD.contentHashCode()
            h = 31 * h + obv.contentHashCode()
            h = 31 * h + moneyFlowIndex.contentHashCode()
            h = 31 * h + keltnerUpper.contentHashCode()
            h = 31 * h + keltnerMiddle.contentHashCode()
            h = 31 * h + keltnerLower.contentHashCode()
            h = 31 * h + donchianUpper.contentHashCode()
            h = 31 * h + donchianMiddle.contentHashCode()
            h = 31 * h + donchianLower.contentHashCode()
            h = 31 * h + (pivotLevels?.hashCode() ?: 0)
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
        val anchoredVwap = if (toggles.anchoredVwap && candles.size >= ANCHORED_VWAP_MIN_BARS)
            AnchoredVwap.calculate(candles, AnchoredVwap.autoAnchorIndex(candles)) else null
        val rsi = if (toggles.rsi && candles.size >= 15)
            TechnicalIndicators.calculateRSI(candles, 14) else null
        val macd = if (toggles.macd && candles.size >= 35)
            TechnicalIndicators.calculateMACD(candles) else null
        val stoch = if (toggles.stochastic && candles.size >= STOCHASTIC_MIN_BARS)
            stochasticOscillator.calculate(candles) else null
        val obv = if (toggles.obv && candles.size >= 2)
            volumeIndicators.obv(candles) else null
        val mfi = if (toggles.moneyFlowIndex && candles.size >= MFI_MIN_BARS)
            volumeIndicators.moneyFlowIndex(candles) else null
        val keltner = if (toggles.keltner && candles.size >= KELTNER_MIN_BARS)
            channelIndicators.keltner(candles) else null
        val donchian = if (toggles.donchian && candles.size >= DONCHIAN_MIN_BARS)
            channelIndicators.donchian(candles) else null
        // Daily pivots need two distinct UTC days of bars; calculateDaily
        // returns null when that is not satisfied, so no extra guard is needed.
        val pivots = if (toggles.pivotPoints) pivotPoints.calculateDaily(candles) else null

        val ichimoku = if (toggles.ichimoku && candles.size >= 52)
            ichimokuCloud.calculate(candles) else null
        val boll = if (toggles.bollinger && candles.size >= 20)
            bollingerBands.calculate(candles) else null
        val st = if (toggles.superTrend && candles.size >= 15)
            superTrend.calculate(candles) else null
        val psar = if (toggles.parabolicSar && candles.size >= 2)
            parabolicSar.calculate(candles).sar else null

        val orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock>
        val fairValueGaps: List<com.foxtrader.app.domain.model.FairValueGap>
        val liquidityPools: List<com.foxtrader.app.domain.model.LiquidityPool>

        if (toggles.orderBlocks && toggles.fairValueGaps) {
            // Use analyzeAll to compute OBs and FVGs once, sharing them across
            // dependent detections (breakers, IFVGs, BPRs).
            val smcResult = smcDetector.analyzeAll(candles)
            orderBlocks = smcResult.orderBlocks
            fairValueGaps = smcResult.fairValueGaps
            liquidityPools = if (toggles.liquidity) smcResult.liquidityPools else emptyList()
        } else {
            orderBlocks = if (toggles.orderBlocks)
                smcDetector.detectOrderBlocks(candles) else emptyList()
            fairValueGaps = if (toggles.fairValueGaps)
                smcDetector.detectFairValueGaps(candles) else emptyList()
            liquidityPools = if (toggles.liquidity)
                smcDetector.detectLiquidity(candles) else emptyList()
        }
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
            anchoredVwap = anchoredVwap,
            rsi = rsi,
            macdLine = macd?.macd,
            macdSignal = macd?.signal,
            macdHistogram = macd?.histogram,
            stochasticK = stoch?.percentK,
            stochasticD = stoch?.percentD,
            obv = obv,
            moneyFlowIndex = mfi,
            keltnerUpper = keltner?.upper,
            keltnerMiddle = keltner?.middle,
            keltnerLower = keltner?.lower,
            donchianUpper = donchian?.upper,
            donchianMiddle = donchian?.middle,
            donchianLower = donchian?.lower,
            pivotLevels = pivots,
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
        // Anchored VWAP is cheap and its auto-anchor may shift as bars arrive,
        // so it is recomputed in full rather than incrementally patched.
        val anchoredVwap = if (toggles.anchoredVwap && candles.size >= ANCHORED_VWAP_MIN_BARS)
            AnchoredVwap.calculate(candles, AnchoredVwap.autoAnchorIndex(candles)) else null
        val ichimoku = if (toggles.ichimoku && candles.size >= 52)
            ichimokuCloud.calculateIncremental(candles, previous.toIchimokuResult(), recomputeFrom) else null
        val boll = if (toggles.bollinger && candles.size >= 20)
            bollingerBands.calculateIncremental(candles, previous.toBollingerResult(candles), recomputeFrom) else null
        val st = if (toggles.superTrend && candles.size >= 15)
            superTrend.calculateIncremental(candles, previous.toSuperTrendResult(), recomputeFrom) else null
        val psar = if (toggles.parabolicSar && candles.size >= 2)
            parabolicSar.calculate(candles).sar else null
        val rsi = if (toggles.rsi && candles.size >= 15)
            TechnicalIndicators.calculateRSI(candles, 14) else null
        val macd = if (toggles.macd && candles.size >= 35)
            TechnicalIndicators.calculateMACD(candles) else null
        // These are all O(n) single-pass (or small-window) computations, so a
        // full recompute stays well inside the frame budget and avoids the
        // seam artefacts an incremental patch would risk.
        val stoch = if (toggles.stochastic && candles.size >= STOCHASTIC_MIN_BARS)
            stochasticOscillator.calculate(candles) else null
        val obv = if (toggles.obv && candles.size >= 2)
            volumeIndicators.obv(candles) else null
        val mfi = if (toggles.moneyFlowIndex && candles.size >= MFI_MIN_BARS)
            volumeIndicators.moneyFlowIndex(candles) else null
        val keltner = if (toggles.keltner && candles.size >= KELTNER_MIN_BARS)
            channelIndicators.keltner(candles) else null
        val donchian = if (toggles.donchian && candles.size >= DONCHIAN_MIN_BARS)
            channelIndicators.donchian(candles) else null
        val pivots = if (toggles.pivotPoints) pivotPoints.calculateDaily(candles) else null

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
            anchoredVwap = anchoredVwap,
            rsi = rsi,
            macdLine = macd?.macd,
            macdSignal = macd?.signal,
            macdHistogram = macd?.histogram,
            stochasticK = stoch?.percentK,
            stochasticD = stoch?.percentD,
            obv = obv,
            moneyFlowIndex = mfi,
            keltnerUpper = keltner?.upper,
            keltnerMiddle = keltner?.middle,
            keltnerLower = keltner?.lower,
            donchianUpper = donchian?.upper,
            donchianMiddle = donchian?.middle,
            donchianLower = donchian?.lower,
            pivotLevels = pivots,
            ichimokuTenkan = ichimoku?.tenkan,
            ichimokuKijun = ichimoku?.kijun,
            ichimokuSenkouA = ichimoku?.senkouA,
            ichimokuSenkouB = ichimoku?.senkouB,
            ichimokuChikou = ichimoku?.chikou,
        )
    }

    private fun Result.toBollingerResult(candles: List<Candle>): BollingerBands.BollingerResult? {
        val upper = bollingerUpper ?: return null
        val middle = bollingerMiddle ?: return null
        val lower = bollingerLower ?: return null
        val percentB = DoubleArray(upper.size)
        val bandwidth = DoubleArray(upper.size)
        for (i in upper.indices) {
            val range = (upper[i] - lower[i]).coerceAtLeast(1e-9)
            val close = candles.getOrNull(i)?.close ?: middle[i]
            percentB[i] = (close - lower[i]) / range
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
        const val ANCHORED_VWAP_MIN_BARS = 20
        const val STOCHASTIC_MIN_BARS = 15
        const val MFI_MIN_BARS = 15
        const val KELTNER_MIN_BARS = 20
        const val DONCHIAN_MIN_BARS = 20
    }
}
