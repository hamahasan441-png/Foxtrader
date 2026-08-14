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
import com.foxtrader.app.domain.usecase.indicators.PivotPoints
import com.foxtrader.app.domain.usecase.indicators.StochasticOscillator
import com.foxtrader.app.domain.usecase.indicators.VolumeIndicators
import com.foxtrader.app.domain.usecase.indicators.IchimokuCloud
import com.foxtrader.app.domain.usecase.indicators.ParabolicSar
import com.foxtrader.app.domain.usecase.indicators.SuperTrend
import com.foxtrader.app.domain.usecase.sessions.SessionDetector
import com.foxtrader.app.domain.usecase.smc.SmcDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChartIndicatorCoordinatorTest {

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
        val explanationEngine = MarketExplanationEngine(
            analyzeStructure = analyzeStructure,
            smcDetector = SmcDetector(),
        )
        coordinator = ChartIndicatorCoordinator(
            analyzeStructure = analyzeStructure,
            computeIndicators = computeIndicators,
            marketExplanationEngine = explanationEngine,
            defaultDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun candles(count: Int, startTimestamp: Long = 1_000L): List<Candle> =
        (0 until count).map { i ->
            Candle(
                timestamp = startTimestamp + i * 60_000L,
                open = 100.0 + i * 0.1,
                high = 101.0 + i * 0.1,
                low = 99.0 + i * 0.1,
                close = 100.5 + i * 0.1,
                volume = 1000.0,
            )
        }

    @Test
    fun `processCandles stores snapshot on first call`() = runBlocking {
        assertNull(coordinator.lastProcessedSnapshot)

        val result = coordinator.processCandles(
            candles = candles(100),
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = false,
        )

        assertNotNull(coordinator.lastProcessedSnapshot)
        assertNotNull(result.bias)
        assertEquals("EURUSD", coordinator.lastProcessedSnapshot!!.symbol)
        assertEquals(100, coordinator.lastProcessedSnapshot!!.candlesSize)
    }

    @Test
    fun `incremental compute succeeds when single bar appended`() = runBlocking {
        val initial = candles(100)
        coordinator.processCandles(
            candles = initial,
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = false,
        )

        // Simulate appending one bar
        val appended = initial + Candle(
            timestamp = initial.last().timestamp + 60_000L,
            open = 200.0,
            high = 201.0,
            low = 199.0,
            close = 200.5,
            volume = 500.0,
        )

        val result = coordinator.processCandles(
            candles = appended,
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = true,
        )

        assertNotNull(result)
        assertEquals(101, coordinator.lastProcessedSnapshot!!.candlesSize)
    }

    @Test
    fun `full recompute when symbol changes even with prefer incremental`() = runBlocking {
        coordinator.processCandles(
            candles = candles(100),
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = false,
        )

        val result = coordinator.processCandles(
            candles = candles(100),
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "GBPUSD",
            timeframe = Timeframe.M15,
            preferIncremental = true,
        )

        assertNotNull(result)
        assertEquals("GBPUSD", coordinator.lastProcessedSnapshot!!.symbol)
    }

    @Test
    fun `full recompute when toggles change even with prefer incremental`() = runBlocking {
        coordinator.processCandles(
            candles = candles(100),
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = false,
        )

        val newToggles = IndicatorToggles(bollinger = true)
        val result = coordinator.processCandles(
            candles = candles(100),
            source = CandleSource.LIVE,
            toggles = newToggles,
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = true,
        )

        assertNotNull(result)
        assertEquals(newToggles, coordinator.lastProcessedSnapshot!!.toggles)
    }

    @Test
    fun `incremental compute handles last bar update`() = runBlocking {
        val initial = candles(100)
        coordinator.processCandles(
            candles = initial,
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = false,
        )

        // Same size, same last timestamp, but modified bar values
        val updated = initial.toMutableList()
        val lastBar = updated.last()
        updated[updated.lastIndex] = lastBar.copy(close = lastBar.close + 1.0)

        val result = coordinator.processCandles(
            candles = updated,
            source = CandleSource.LIVE,
            toggles = IndicatorToggles(),
            symbol = "EURUSD",
            timeframe = Timeframe.M15,
            preferIncremental = true,
        )

        assertNotNull(result)
        assertEquals(100, coordinator.lastProcessedSnapshot!!.candlesSize)
    }
}
