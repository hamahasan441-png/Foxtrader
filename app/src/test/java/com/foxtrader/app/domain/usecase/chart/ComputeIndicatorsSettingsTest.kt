package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.usecase.analysis.FibonacciEngine
import com.foxtrader.app.domain.usecase.analysis.MarketProfile
import com.foxtrader.app.domain.usecase.analysis.SupportResistanceDetector
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
import com.foxtrader.app.feature.chart.presentation.ChartStudySettings
import com.foxtrader.app.feature.chart.presentation.EmaStudySettings
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import com.foxtrader.app.feature.chart.presentation.RsiStudySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComputeIndicatorsSettingsTest {
    private lateinit var useCase: ComputeIndicatorsUseCase

    @Before
    fun setUp() {
        useCase = ComputeIndicatorsUseCase(
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
    }

    @Test
    fun `EMA minimum bars follows configured periods`() {
        val candles = candles(30)
        val settings = ChartStudySettings(ema = EmaStudySettings(fastPeriod = 5, slowPeriod = 25))
        val result = useCase(candles, IndicatorToggles(ema = true, settings = settings))
        assertNotNull(result.emaShort)
        assertNotNull(result.emaLong)
        assertEquals(30, result.emaLong!!.size)

        val notReady = useCase(
            candles,
            IndicatorToggles(
                ema = true,
                settings = settings.copy(ema = EmaStudySettings(fastPeriod = 20, slowPeriod = 50)),
            ),
        )
        assertNotNull(notReady.emaShort)
        assertNull(notReady.emaLong)
    }

    @Test
    fun `changing RSI period changes the calculation instead of only the label`() {
        val candles = oscillatingCandles(120)
        val fast = useCase(
            candles,
            IndicatorToggles(
                rsi = true,
                settings = ChartStudySettings(rsi = RsiStudySettings(period = 5)),
            ),
        ).rsi
        val slow = useCase(
            candles,
            IndicatorToggles(
                rsi = true,
                settings = ChartStudySettings(rsi = RsiStudySettings(period = 30)),
            ),
        ).rsi

        assertNotNull(fast)
        assertNotNull(slow)
        assertTrue(kotlin.math.abs(fast!!.last() - slow!!.last()) > 1e-6)
    }

    @Test
    fun `bad provider volume cannot suppress price-only studies`() {
        val source = candles(80).toMutableList()
        source[40] = source[40].copy(volume = Double.NaN)
        val result = useCase(
            source,
            IndicatorToggles(ema = true, rsi = true, bollinger = true),
        )
        assertNotNull(result.emaShort)
        assertNotNull(result.emaLong)
        assertNotNull(result.rsi)
        assertNotNull(result.bollingerUpper)
        assertTrue(result.emaLong!!.all { it.isFinite() })
    }

    @Test
    fun `bad price still fails closed for every study`() {
        val source = candles(80).toMutableList()
        source[40] = source[40].copy(high = source[40].low - 1.0)
        val result = useCase(
            source,
            IndicatorToggles(ema = true, rsi = true, bollinger = true),
        )
        assertNull(result.emaShort)
        assertNull(result.rsi)
        assertNull(result.bollingerUpper)
    }

    private fun candles(count: Int): List<Candle> = (0 until count).map { i ->
        val price = 100.0 + i * 0.2
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = price - 0.1,
            high = price + 0.8,
            low = price - 0.8,
            close = price + 0.1,
            volume = 1_000.0 + i,
        )
    }

    private fun oscillatingCandles(count: Int): List<Candle> = (0 until count).map { i ->
        val price = 100.0 + kotlin.math.sin(i / 2.5) * 5.0 + i * 0.02
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = price - 0.3,
            high = price + 1.0,
            low = price - 1.0,
            close = price + kotlin.math.sin(i / 1.7) * 0.4,
            volume = 900.0 + (i % 7) * 30.0,
        )
    }
}
