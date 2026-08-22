package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.ai.MarketExplanation
import com.foxtrader.app.domain.usecase.ai.MarketExplanationEngine
import com.foxtrader.app.domain.usecase.chart.ComputeIndicatorsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Handles chart indicator computation and structure analysis off the main thread.
 *
 * Engine boundaries are isolated: a structure/explanation failure must not turn
 * an indicator tap into an app crash or erase unrelated overlay output.
 */
internal class ChartIndicatorCoordinator(
    private val analyzeStructure: AnalyzeMarketStructureUseCase,
    private val computeIndicators: ComputeIndicatorsUseCase,
    private val marketExplanationEngine: MarketExplanationEngine,
    private val defaultDispatcher: CoroutineDispatcher,
) {

    var lastProcessedSnapshot: ProcessedSnapshot? = null

    suspend fun processCandles(
        candles: List<Candle>,
        source: CandleSource,
        toggles: IndicatorToggles,
        symbol: String,
        timeframe: Timeframe,
        preferIncremental: Boolean = false,
    ): ChartComputation {
        val computation = computeFrame(
            candles = candles,
            toggles = toggles,
            preferIncremental = preferIncremental,
            symbol = symbol,
            timeframe = timeframe,
        )

        lastProcessedSnapshot = ProcessedSnapshot(
            symbol = symbol,
            timeframe = timeframe,
            toggles = toggles,
            candlesSize = candles.size,
            firstTimestamp = candles.firstOrNull()?.timestamp,
            lastTimestamp = candles.lastOrNull()?.timestamp,
            bias = computation.bias,
            structureBreaks = computation.structureBreaks,
            overlays = computation.overlays,
            marketExplanation = computation.marketExplanation,
        )

        return computation
    }

    private suspend fun computeFrame(
        candles: List<Candle>,
        toggles: IndicatorToggles,
        preferIncremental: Boolean,
        symbol: String,
        timeframe: Timeframe,
    ): ChartComputation = withContext(defaultDispatcher) {
        val incremental = if (preferIncremental) {
            computeIncrementalFrame(candles, toggles, symbol, timeframe)
        } else null
        incremental ?: computeFullFrame(candles, toggles, symbol, timeframe)
    }

    private fun computeFullFrame(
        candles: List<Candle>,
        toggles: IndicatorToggles,
        symbol: String,
        timeframe: Timeframe,
    ): ChartComputation {
        val structure = containedOrNull { analyzeStructure(candles) }
        val overlays = computeIndicators(candles, toggles)
        val explanation = if (candles.size >= 50) {
            containedOrNull {
                marketExplanationEngine.explain(
                    symbol = symbol,
                    timeframe = timeframe,
                    candles = candles.asCandleSeries(),
                )
            }
        } else null

        return ChartComputation(
            bias = structure?.bias ?: Bias.NEUTRAL,
            structureBreaks = structure?.breaks.orEmpty(),
            overlays = overlays,
            marketExplanation = explanation,
        )
    }

    private fun computeIncrementalFrame(
        candles: List<Candle>,
        toggles: IndicatorToggles,
        symbol: String,
        timeframe: Timeframe,
    ): ChartComputation? {
        val previous = lastProcessedSnapshot ?: return null
        if (previous.symbol != symbol || previous.timeframe != timeframe) return null
        if (previous.toggles != toggles) return null
        if (previous.firstTimestamp != candles.firstOrNull()?.timestamp) return null
        if (toggles.volumeProfile || toggles.marketProfile) return null

        val size = candles.size
        val previousSize = previous.candlesSize
        val isLastBarUpdate = size == previousSize && previous.lastTimestamp == candles.lastOrNull()?.timestamp
        val isAppend = size == previousSize + 1 && previous.lastTimestamp == candles.getOrNull(candles.lastIndex - 1)?.timestamp
        if (!isLastBarUpdate && !isAppend) return null

        val windowStart = (size - INCREMENTAL_ANALYSIS_WINDOW).coerceAtLeast(0)
        val windowCandles = candles.subList(windowStart, size)
        val structure = containedOrNull { analyzeStructure(windowCandles) }
        val overlays = computeIndicators(windowCandles, toggles)
        val explanation = when {
            candles.size < 50 -> null
            isLastBarUpdate -> previous.marketExplanation
            else -> containedOrNull {
                marketExplanationEngine.explain(
                    symbol = symbol,
                    timeframe = timeframe,
                    candles = candles.asCandleSeries(),
                )
            }
        }

        return ChartComputation(
            bias = structure?.bias ?: previous.bias,
            structureBreaks = if (structure == null) {
                previous.structureBreaks
            } else {
                previous.structureBreaks.filter { it.breakIndex < windowStart } +
                    structure.breaks.map { it.shift(windowStart) }
            },
            overlays = mergeOverlayWindow(candles, previous.overlays, overlays, toggles, windowStart),
            marketExplanation = explanation,
        )
    }

    private fun mergeOverlayWindow(
        candles: List<Candle>,
        previous: ComputeIndicatorsUseCase.Result,
        window: ComputeIndicatorsUseCase.Result,
        toggles: IndicatorToggles,
        windowStart: Int,
    ): ComputeIndicatorsUseCase.Result {
        val visuals = computeIndicators.computeIncrementalVisuals(
            candles = candles.asCandleSeries(),
            toggles = toggles,
            previous = previous,
            recomputeFrom = windowStart,
        )
        return ComputeIndicatorsUseCase.Result(
            emaShort = visuals.emaShort,
            emaLong = visuals.emaLong,
            bollingerUpper = visuals.bollingerUpper,
            bollingerMiddle = visuals.bollingerMiddle,
            bollingerLower = visuals.bollingerLower,
            superTrendValues = visuals.superTrendValues,
            superTrendDir = visuals.superTrendDir,
            superTrendFinalUpper = visuals.superTrendFinalUpper,
            superTrendFinalLower = visuals.superTrendFinalLower,
            parabolicSar = visuals.parabolicSar,
            vwap = visuals.vwap,
            anchoredVwap = visuals.anchoredVwap,
            rsi = visuals.rsi,
            macdLine = visuals.macdLine,
            macdSignal = visuals.macdSignal,
            macdHistogram = visuals.macdHistogram,
            stochasticK = visuals.stochasticK,
            stochasticD = visuals.stochasticD,
            obv = visuals.obv,
            moneyFlowIndex = visuals.moneyFlowIndex,
            keltnerUpper = visuals.keltnerUpper,
            keltnerMiddle = visuals.keltnerMiddle,
            keltnerLower = visuals.keltnerLower,
            donchianUpper = visuals.donchianUpper,
            donchianMiddle = visuals.donchianMiddle,
            donchianLower = visuals.donchianLower,
            pivotLevels = visuals.pivotLevels,
            ichimokuTenkan = visuals.ichimokuTenkan,
            ichimokuKijun = visuals.ichimokuKijun,
            ichimokuSenkouA = visuals.ichimokuSenkouA,
            ichimokuSenkouB = visuals.ichimokuSenkouB,
            ichimokuChikou = visuals.ichimokuChikou,
            orderBlocks = previous.orderBlocks.filter { it.endIndex < windowStart } +
                window.orderBlocks.map { it.shift(windowStart) },
            fairValueGaps = previous.fairValueGaps.filter { it.index < windowStart } +
                window.fairValueGaps.map { it.shift(windowStart) },
            liquidityPools = previous.liquidityPools.filter { it.endIndex < windowStart } +
                window.liquidityPools.map { it.shift(windowStart) },
            volumeProfile = window.volumeProfile,
            marketProfile = window.marketProfile,
            supportResistanceZones = window.supportResistanceZones,
            autoFibLevels = window.autoFibLevels,
            autoFibDirection = window.autoFibDirection,
            autoFibSwingHigh = window.autoFibSwingHigh,
            autoFibSwingLow = window.autoFibSwingLow,
            sessions = previous.sessions.filter { it.endIndex < windowStart } +
                window.sessions.map { it.shift(windowStart) },
        )
    }

    private inline fun <T> containedOrNull(block: () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        null
    }

    companion object {
        const val INCREMENTAL_ANALYSIS_WINDOW = 320
    }
}

internal fun com.foxtrader.app.domain.model.StructureBreak.shift(offset: Int) = copy(
    breakIndex = breakIndex + offset,
)

internal fun com.foxtrader.app.domain.model.OrderBlock.shift(offset: Int) = copy(
    startIndex = startIndex + offset,
    endIndex = endIndex + offset,
)

internal fun com.foxtrader.app.domain.model.FairValueGap.shift(offset: Int) = copy(
    index = index + offset,
)

internal fun com.foxtrader.app.domain.model.LiquidityPool.shift(offset: Int) = copy(
    startIndex = startIndex + offset,
    endIndex = endIndex + offset,
    sweepIndex = sweepIndex?.plus(offset),
)

internal fun com.foxtrader.app.domain.model.SessionRange.shift(offset: Int) = copy(
    startIndex = startIndex + offset,
    endIndex = endIndex + offset,
)

internal data class ProcessedSnapshot(
    val symbol: String,
    val timeframe: Timeframe,
    val toggles: IndicatorToggles,
    val candlesSize: Int,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?,
    val bias: Bias,
    val structureBreaks: List<com.foxtrader.app.domain.model.StructureBreak>,
    val overlays: ComputeIndicatorsUseCase.Result,
    val marketExplanation: MarketExplanation? = null,
)

internal data class ChartComputation(
    val bias: Bias,
    val structureBreaks: List<com.foxtrader.app.domain.model.StructureBreak>,
    val overlays: ComputeIndicatorsUseCase.Result,
    val marketExplanation: MarketExplanation?,
)
