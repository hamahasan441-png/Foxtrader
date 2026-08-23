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
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max

/**
 * Domain use case that computes all chart overlays (indicators + SMC + sessions)
 * from a candle series and the active chart settings.
 *
 * Each study is isolated at this boundary. One defective/custom indicator can
 * degrade its own output to null/empty without aborting the rest of the frame.
 * A missing/non-finite volume value is normalized to zero instead of suppressing
 * price-only studies such as EMA/RSI/Bollinger.
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

    operator fun invoke(candles: List<Candle>, toggles: IndicatorToggles): Result {
        if (!arePricesWellFormed(candles)) return emptyResult()
        val safeCandles = normalizeVolume(candles)
        val settings = toggles.settings.sanitized()

        val emaShort = if (toggles.ema && safeCandles.size >= settings.ema.fastPeriod)
            safeOrNull { TechnicalIndicators.calculateEMA(safeCandles, settings.ema.fastPeriod) } else null
        val emaLong = if (toggles.ema && safeCandles.size >= settings.ema.slowPeriod)
            safeOrNull { TechnicalIndicators.calculateEMA(safeCandles, settings.ema.slowPeriod) } else null
        val vwap = if (toggles.vwap && safeCandles.isNotEmpty())
            safeOrNull { TechnicalIndicators.calculateVWAP(safeCandles) } else null
        val anchoredVwap = if (toggles.anchoredVwap && safeCandles.size >= ANCHORED_VWAP_MIN_BARS)
            safeOrNull { AnchoredVwap.calculate(safeCandles, AnchoredVwap.autoAnchorIndex(safeCandles)) } else null
        val rsi = if (toggles.rsi && safeCandles.size >= settings.rsi.period + 1)
            safeOrNull { TechnicalIndicators.calculateRSI(safeCandles, settings.rsi.period) } else null
        val macdMinBars = max(settings.macd.fastPeriod, settings.macd.slowPeriod) + settings.macd.signalPeriod
        val macd = if (toggles.macd && safeCandles.size >= macdMinBars)
            safeOrNull {
                TechnicalIndicators.calculateMACD(
                    safeCandles,
                    fast = settings.macd.fastPeriod,
                    slow = settings.macd.slowPeriod,
                    signalPeriod = settings.macd.signalPeriod,
                )
            } else null
        val stochMinBars = max(settings.stochastic.kPeriod, settings.stochastic.dPeriod) + 1
        val stoch = if (toggles.stochastic && safeCandles.size >= stochMinBars)
            safeOrNull {
                stochasticOscillator.calculate(
                    safeCandles,
                    kPeriod = settings.stochastic.kPeriod,
                    dPeriod = settings.stochastic.dPeriod,
                )
            } else null
        val obv = if (toggles.obv && safeCandles.size >= 2)
            safeOrNull { volumeIndicators.obv(safeCandles) } else null
        val mfi = if (toggles.moneyFlowIndex && safeCandles.size >= settings.mfi.period + 1)
            safeOrNull { volumeIndicators.moneyFlowIndex(safeCandles, settings.mfi.period) } else null
        val keltnerMinBars = max(settings.keltner.emaPeriod, settings.keltner.atrPeriod) + 1
        val keltner = if (toggles.keltner && safeCandles.size >= keltnerMinBars)
            safeOrNull {
                channelIndicators.keltner(
                    safeCandles,
                    emaPeriod = settings.keltner.emaPeriod,
                    atrPeriod = settings.keltner.atrPeriod,
                    multiplier = settings.keltner.multiplier,
                )
            } else null
        val donchian = if (toggles.donchian && safeCandles.size >= settings.donchian.period)
            safeOrNull { channelIndicators.donchian(safeCandles, settings.donchian.period) } else null
        val pivots = if (toggles.pivotPoints) safeOrNull { pivotPoints.calculateDaily(safeCandles) } else null

        val ichimokuMinBars = maxOf(
            settings.ichimoku.tenkanPeriod,
            settings.ichimoku.kijunPeriod,
            settings.ichimoku.senkouBPeriod,
        )
        val ichimoku = if (toggles.ichimoku && safeCandles.size >= ichimokuMinBars)
            safeOrNull {
                ichimokuCloud.calculate(
                    safeCandles,
                    tenkanPeriod = settings.ichimoku.tenkanPeriod,
                    kijunPeriod = settings.ichimoku.kijunPeriod,
                    senkouBPeriod = settings.ichimoku.senkouBPeriod,
                    displacement = settings.ichimoku.displacement,
                )
            } else null
        val boll = if (toggles.bollinger && safeCandles.size >= settings.bollinger.period)
            safeOrNull {
                bollingerBands.calculate(
                    safeCandles,
                    period = settings.bollinger.period,
                    multiplier = settings.bollinger.multiplier,
                )
            } else null
        val st = if (toggles.superTrend && safeCandles.size >= settings.superTrend.atrPeriod + 2)
            safeOrNull {
                superTrend.calculate(
                    safeCandles,
                    atrPeriod = settings.superTrend.atrPeriod,
                    multiplier = settings.superTrend.multiplier,
                )
            } else null
        val psar = if (toggles.parabolicSar && safeCandles.size >= 2)
            safeOrNull {
                parabolicSar.calculate(
                    safeCandles,
                    accelerationStart = settings.parabolicSar.accelerationStart,
                    accelerationStep = settings.parabolicSar.accelerationStep,
                    accelerationMax = settings.parabolicSar.accelerationMax,
                ).sar
            } else null

        var orderBlocks: List<com.foxtrader.app.domain.model.OrderBlock> = emptyList()
        var fairValueGaps: List<com.foxtrader.app.domain.model.FairValueGap> = emptyList()
        var liquidityPools: List<com.foxtrader.app.domain.model.LiquidityPool> = emptyList()

        if (toggles.orderBlocks && toggles.fairValueGaps) {
            val smcResult = safeOrNull { smcDetector.analyzeAll(safeCandles) }
            if (smcResult != null) {
                orderBlocks = smcResult.orderBlocks
                fairValueGaps = smcResult.fairValueGaps
                if (toggles.liquidity) liquidityPools = smcResult.liquidityPools
            } else {
                orderBlocks = safeOrDefault(emptyList()) { smcDetector.detectOrderBlocks(safeCandles) }
                fairValueGaps = safeOrDefault(emptyList()) { smcDetector.detectFairValueGaps(safeCandles) }
                if (toggles.liquidity) {
                    liquidityPools = safeOrDefault(emptyList()) { smcDetector.detectLiquidity(safeCandles) }
                }
            }
        } else {
            if (toggles.orderBlocks) {
                orderBlocks = safeOrDefault(emptyList()) { smcDetector.detectOrderBlocks(safeCandles) }
            }
            if (toggles.fairValueGaps) {
                fairValueGaps = safeOrDefault(emptyList()) { smcDetector.detectFairValueGaps(safeCandles) }
            }
            if (toggles.liquidity) {
                liquidityPools = safeOrDefault(emptyList()) { smcDetector.detectLiquidity(safeCandles) }
            }
        }

        val volumeProfile = if (toggles.volumeProfile && safeCandles.size >= 20)
            safeOrNull { smcDetector.computeVolumeProfile(safeCandles) } else null
        val marketProfileResult = if (toggles.marketProfile && safeCandles.size >= 30)
            safeOrNull { marketProfile.compute(safeCandles) } else null
        val supportResistanceZones = if (toggles.supportResistance && safeCandles.size >= 25)
            safeOrDefault(emptyList()) { supportResistanceDetector.detect(safeCandles) } else emptyList()
        val autoFib = if (toggles.fibonacci && safeCandles.size >= AUTO_FIB_MIN_BARS)
            safeOrNull { buildAutoFib(safeCandles) } else null
        val sessions = if (toggles.sessions)
            safeOrDefault(emptyList()) { sessionDetector.detectSessions(safeCandles) } else emptyList()

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
            orderBlocks = capRecent(orderBlocks, SMC_RENDER_CAP),
            fairValueGaps = capRecent(fairValueGaps, SMC_RENDER_CAP),
            liquidityPools = capRecent(liquidityPools, SMC_RENDER_CAP),
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
        if (!arePricesWellFormed(candles)) return previous
        val safeCandles = normalizeVolume(candles)
        val settings = toggles.settings.sanitized()

        val emaShort = if (toggles.ema && safeCandles.size >= settings.ema.fastPeriod)
            safeOrPrevious(previous.emaShort) {
                TechnicalIndicators.calculateEMAIncremental(
                    safeCandles, settings.ema.fastPeriod, previous.emaShort, recomputeFrom,
                )
            } else null
        val emaLong = if (toggles.ema && safeCandles.size >= settings.ema.slowPeriod)
            safeOrPrevious(previous.emaLong) {
                TechnicalIndicators.calculateEMAIncremental(
                    safeCandles, settings.ema.slowPeriod, previous.emaLong, recomputeFrom,
                )
            } else null
        val vwap = if (toggles.vwap && safeCandles.isNotEmpty())
            safeOrPrevious(previous.vwap) {
                TechnicalIndicators.calculateVWAPIncremental(safeCandles, previous.vwap, recomputeFrom)
            } else null
        val anchoredVwap = if (toggles.anchoredVwap && safeCandles.size >= ANCHORED_VWAP_MIN_BARS)
            safeOrPrevious(previous.anchoredVwap) {
                AnchoredVwap.calculate(safeCandles, AnchoredVwap.autoAnchorIndex(safeCandles))
            } else null
        val ichimokuMinBars = maxOf(
            settings.ichimoku.tenkanPeriod,
            settings.ichimoku.kijunPeriod,
            settings.ichimoku.senkouBPeriod,
        )
        val ichimoku = if (toggles.ichimoku && safeCandles.size >= ichimokuMinBars)
            safeOrPrevious(previous.toIchimokuResult(settings.ichimoku.displacement)) {
                ichimokuCloud.calculateIncremental(
                    safeCandles,
                    previous.toIchimokuResult(settings.ichimoku.displacement),
                    recomputeFrom,
                    tenkanPeriod = settings.ichimoku.tenkanPeriod,
                    kijunPeriod = settings.ichimoku.kijunPeriod,
                    senkouBPeriod = settings.ichimoku.senkouBPeriod,
                    displacement = settings.ichimoku.displacement,
                )
            } else null
        val boll = if (toggles.bollinger && safeCandles.size >= settings.bollinger.period)
            safeOrPrevious(previous.toBollingerResult(safeCandles)) {
                bollingerBands.calculateIncremental(
                    safeCandles,
                    previous.toBollingerResult(safeCandles),
                    recomputeFrom,
                    period = settings.bollinger.period,
                    multiplier = settings.bollinger.multiplier,
                )
            } else null
        val st = if (toggles.superTrend && safeCandles.size >= settings.superTrend.atrPeriod + 2)
            safeOrPrevious(previous.toSuperTrendResult()) {
                superTrend.calculateIncremental(
                    safeCandles,
                    previous.toSuperTrendResult(),
                    recomputeFrom,
                    atrPeriod = settings.superTrend.atrPeriod,
                    multiplier = settings.superTrend.multiplier,
                )
            } else null
        val psar = if (toggles.parabolicSar && safeCandles.size >= 2)
            safeOrPrevious(previous.parabolicSar) {
                parabolicSar.calculate(
                    safeCandles,
                    accelerationStart = settings.parabolicSar.accelerationStart,
                    accelerationStep = settings.parabolicSar.accelerationStep,
                    accelerationMax = settings.parabolicSar.accelerationMax,
                ).sar
            } else null
        val rsi = if (toggles.rsi && safeCandles.size >= settings.rsi.period + 1)
            safeOrPrevious(previous.rsi) {
                TechnicalIndicators.calculateRSI(safeCandles, settings.rsi.period)
            } else null
        val macdMinBars = max(settings.macd.fastPeriod, settings.macd.slowPeriod) + settings.macd.signalPeriod
        val macd = if (toggles.macd && safeCandles.size >= macdMinBars)
            safeOrNull {
                TechnicalIndicators.calculateMACD(
                    safeCandles,
                    fast = settings.macd.fastPeriod,
                    slow = settings.macd.slowPeriod,
                    signalPeriod = settings.macd.signalPeriod,
                )
            } else null
        val stochMinBars = max(settings.stochastic.kPeriod, settings.stochastic.dPeriod) + 1
        val stoch = if (toggles.stochastic && safeCandles.size >= stochMinBars)
            safeOrNull {
                stochasticOscillator.calculate(
                    safeCandles,
                    kPeriod = settings.stochastic.kPeriod,
                    dPeriod = settings.stochastic.dPeriod,
                )
            } else null
        val obv = if (toggles.obv && safeCandles.size >= 2)
            safeOrPrevious(previous.obv) { volumeIndicators.obv(safeCandles) } else null
        val mfi = if (toggles.moneyFlowIndex && safeCandles.size >= settings.mfi.period + 1)
            safeOrPrevious(previous.moneyFlowIndex) {
                volumeIndicators.moneyFlowIndex(safeCandles, settings.mfi.period)
            } else null
        val keltnerMinBars = max(settings.keltner.emaPeriod, settings.keltner.atrPeriod) + 1
        val keltner = if (toggles.keltner && safeCandles.size >= keltnerMinBars)
            safeOrNull {
                channelIndicators.keltner(
                    safeCandles,
                    emaPeriod = settings.keltner.emaPeriod,
                    atrPeriod = settings.keltner.atrPeriod,
                    multiplier = settings.keltner.multiplier,
                )
            } else null
        val donchian = if (toggles.donchian && safeCandles.size >= settings.donchian.period)
            safeOrNull { channelIndicators.donchian(safeCandles, settings.donchian.period) } else null
        val pivots = if (toggles.pivotPoints)
            safeOrPrevious(previous.pivotLevels) { pivotPoints.calculateDaily(safeCandles) } else null

        return previous.copy(
            emaShort = emaShort,
            emaLong = emaLong,
            bollingerUpper = boll?.upper ?: previous.bollingerUpper.takeIf { toggles.bollinger },
            bollingerMiddle = boll?.middle ?: previous.bollingerMiddle.takeIf { toggles.bollinger },
            bollingerLower = boll?.lower ?: previous.bollingerLower.takeIf { toggles.bollinger },
            superTrendValues = st?.values ?: previous.superTrendValues.takeIf { toggles.superTrend },
            superTrendDir = st?.direction ?: previous.superTrendDir.takeIf { toggles.superTrend },
            superTrendFinalUpper = st?.finalUpperBands ?: previous.superTrendFinalUpper.takeIf { toggles.superTrend },
            superTrendFinalLower = st?.finalLowerBands ?: previous.superTrendFinalLower.takeIf { toggles.superTrend },
            parabolicSar = psar,
            vwap = vwap,
            anchoredVwap = anchoredVwap,
            rsi = rsi,
            macdLine = macd?.macd ?: previous.macdLine.takeIf { toggles.macd },
            macdSignal = macd?.signal ?: previous.macdSignal.takeIf { toggles.macd },
            macdHistogram = macd?.histogram ?: previous.macdHistogram.takeIf { toggles.macd },
            stochasticK = stoch?.percentK ?: previous.stochasticK.takeIf { toggles.stochastic },
            stochasticD = stoch?.percentD ?: previous.stochasticD.takeIf { toggles.stochastic },
            obv = obv,
            moneyFlowIndex = mfi,
            keltnerUpper = keltner?.upper ?: previous.keltnerUpper.takeIf { toggles.keltner },
            keltnerMiddle = keltner?.middle ?: previous.keltnerMiddle.takeIf { toggles.keltner },
            keltnerLower = keltner?.lower ?: previous.keltnerLower.takeIf { toggles.keltner },
            donchianUpper = donchian?.upper ?: previous.donchianUpper.takeIf { toggles.donchian },
            donchianMiddle = donchian?.middle ?: previous.donchianMiddle.takeIf { toggles.donchian },
            donchianLower = donchian?.lower ?: previous.donchianLower.takeIf { toggles.donchian },
            pivotLevels = pivots,
            ichimokuTenkan = ichimoku?.tenkan ?: previous.ichimokuTenkan.takeIf { toggles.ichimoku },
            ichimokuKijun = ichimoku?.kijun ?: previous.ichimokuKijun.takeIf { toggles.ichimoku },
            ichimokuSenkouA = ichimoku?.senkouA ?: previous.ichimokuSenkouA.takeIf { toggles.ichimoku },
            ichimokuSenkouB = ichimoku?.senkouB ?: previous.ichimokuSenkouB.takeIf { toggles.ichimoku },
            ichimokuChikou = ichimoku?.chikou ?: previous.ichimokuChikou.takeIf { toggles.ichimoku },
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

    private fun Result.toIchimokuResult(displacement: Int): IchimokuCloud.IchimokuResult? {
        val tenkan = ichimokuTenkan ?: return null
        val kijun = ichimokuKijun ?: return null
        val senkouA = ichimokuSenkouA ?: return null
        val senkouB = ichimokuSenkouB ?: return null
        val chikou = ichimokuChikou ?: return null
        return IchimokuCloud.IchimokuResult(tenkan, kijun, senkouA, senkouB, chikou, displacement = displacement)
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

    /**
     * Reject corrupt price bars globally because every study depends on OHLC.
     * Volume is deliberately excluded: feeds frequently omit it, and price-only
     * studies must continue to work in that case.
     */
    private fun arePricesWellFormed(candles: List<Candle>): Boolean =
        candles.all { c ->
            c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite() &&
                c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 &&
                c.high >= maxOf(c.open, c.close, c.low) &&
                c.low <= minOf(c.open, c.close, c.high)
        }

    /** Convert missing/bad provider volume to an explicit unavailable value. */
    private fun normalizeVolume(candles: List<Candle>): List<Candle> {
        if (candles.all { it.volume.isFinite() && it.volume >= 0.0 }) return candles
        return candles.map { candle ->
            if (candle.volume.isFinite() && candle.volume >= 0.0) candle else candle.copy(volume = 0.0)
        }
    }

    private inline fun <T> safeOrNull(block: () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    private inline fun <T> safeOrDefault(default: T, block: () -> T): T = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        default
    }

    private inline fun <T> safeOrPrevious(previous: T?, block: () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        previous
    }

    private fun <T> capRecent(list: List<T>, max: Int): List<T> =
        if (list.size <= max) list else list.takeLast(max)

    private fun emptyResult(): Result = Result(
        emaShort = null,
        emaLong = null,
        bollingerUpper = null,
        bollingerMiddle = null,
        bollingerLower = null,
        superTrendValues = null,
        superTrendDir = null,
        superTrendFinalUpper = null,
        superTrendFinalLower = null,
        parabolicSar = null,
        vwap = null,
        anchoredVwap = null,
        rsi = null,
        macdLine = null,
        macdSignal = null,
        macdHistogram = null,
        stochasticK = null,
        stochasticD = null,
        obv = null,
        moneyFlowIndex = null,
        keltnerUpper = null,
        keltnerMiddle = null,
        keltnerLower = null,
        donchianUpper = null,
        donchianMiddle = null,
        donchianLower = null,
        pivotLevels = null,
        ichimokuTenkan = null,
        ichimokuKijun = null,
        ichimokuSenkouA = null,
        ichimokuSenkouB = null,
        ichimokuChikou = null,
        orderBlocks = emptyList(),
        fairValueGaps = emptyList(),
        liquidityPools = emptyList(),
        volumeProfile = null,
        marketProfile = null,
        supportResistanceZones = emptyList(),
        autoFibLevels = emptyList(),
        autoFibDirection = null,
        autoFibSwingHigh = null,
        autoFibSwingLow = null,
        sessions = emptyList(),
    )

    private companion object {
        const val AUTO_FIB_LOOKBACK = 120
        const val AUTO_FIB_MIN_BARS = 30
        const val AUTO_FIB_MIN_SWING_BARS = 6
        const val ANCHORED_VWAP_MIN_BARS = 20
        const val SMC_RENDER_CAP = 80
    }
}
