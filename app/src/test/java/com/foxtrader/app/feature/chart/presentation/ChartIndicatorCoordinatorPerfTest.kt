package com.foxtrader.app.feature.chart.presentation

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.CandleSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.AnalyzeMarketStructureUseCase
import com.foxtrader.app.domain.usecase.ai.MarketExplanationEngine
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector
import com.foxtrader.app.domain.usecase.chart.ComputeIndicatorsUseCase
import com.foxtrader.app.domain.usecase.indicators.BollingerBands
import com.foxtrader.app.domain.usecase.indicators.ChannelIndicators
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.ParabolicSar
import com.foxtrader.app.domain.usecase.indicators.PivotPoints
import com.foxtrader.app.domain.usecase.indicators.StochasticOscillator
import com.foxtrader.app.domain.usecase.indicators.SuperTrend
import com.foxtrader.app.domain.usecase.indicators.VolumeIndicators
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Pins the incremental-frame performance contract:
 *
 * - An intra-bar tick update (same bar count, same last timestamp) must REUSE
 *   the previous market explanation instead of re-running the narrative engine
 *   (full structure + SMC over the whole series) at tick frequency.
 * - A closed-bar append must produce a FRESH explanation.
 */
class ChartIndicatorCoordinatorPerfTest {

    private lateinit var coordinator: ChartIndicatorCoordinator

    @Before
    fun setUp() {
        val analyzeStructure = AnalyzeMarketStructureUseCase()
        val computeIndicators = ComputeIndicatorsUseCase(
            bollingerBands = BollingerBands(),
            ichimokuCloud = IchimokuCloud(),
            superTrend = SuperTrend(),
            parabolicSar = ParabolicSar(),
            smcDetector = SmcDetector(),
            sessionDetector = SessionDetector(),
            marketProfile = MarketProfile(),
            supportResistanceDetector = SupportResistanceDetector(),
            fibonacciEngine = FibonacciEngine(),
            channelIndicators = ChannelIndicators(),
            stochasticOscillator = StochasticOscillator(),
            volumeIndicators = VolumeIndicators(),
            pivotPoints = PivotPoints(),
        )
        coordinator = ChartIndicatorCoordinator(
            analyzeStructure = analyzeStructure,
            computeIndicators = computeIndicators,
            marketExplanationEngine = MarketExplanationEngine(
                analyzeStructure = analyzeStructure,
                smcDetector = SmcDetector(),
            ),
            defaultDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun candles(count: Int): List<Candle> =
        (0 until count).map { i ->
            Candle(
                timestamp = 1_000L + i * 60_000L,
                open = 100.0 + i * 0.1,
                high = 101.0 + i * 0.1,
                low = 99.0 + i * 0.1,
                close = 100.5 + i * 0.1,
                volume = 1000.0,
            )
        }

    @Test
    fun `intra-bar tick reuses the previous market explanation instance`() = runBlocking {
        val initial = candles(100)
        val first = coordinator.processCandles(
            candles = initial,
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = false,
        )
        assertNotNull(first.marketExplanation)

        // Same size, same last timestamp — a forming-bar tick.
        val ticked = initial.toMutableList()
        val lastBar = ticked.last()
        ticked[ticked.lastIndex] = lastBar.copy(close = lastBar.close + 0.5)

        val second = coordinator.processCandles(
            candles = ticked,
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = true,
        )

        assertSame(
            "tick updates must not re-run the narrative engine",
            first.marketExplanation,
            second.marketExplanation,
        )
    }

    @Test
    fun `closed-bar append recomputes the market explanation`() = runBlocking {
        val initial = candles(100)
        val first = coordinator.processCandles(
            candles = initial,
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = false,
        )

        val appended = initial + Candle(
            timestamp = initial.last().timestamp + 60_000L,
            open = 110.0,
            high = 111.0,
            low = 109.0,
            close = 110.5,
            volume = 500.0,
        )

        val second = coordinator.processCandles(
            candles = appended,
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = true,
        )

        assertNotNull(second.marketExplanation)
        assertNotSame(
            "a closed bar must refresh the narrative",
            first.marketExplanation,
            second.marketExplanation,
        )
    }
}
