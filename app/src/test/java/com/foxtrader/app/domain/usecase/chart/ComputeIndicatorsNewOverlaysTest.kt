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
import com.foxtrader.app.feature.chart.presentation.IndicatorToggles
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Verifies the newly-wired chart overlays and study panes (Keltner, Donchian,
 * Pivots, Stochastic, OBV, MFI) are computed by [ComputeIndicatorsUseCase] and
 * are correctly gated by their toggles.
 */
class ComputeIndicatorsNewOverlaysTest {

    private val useCase = ComputeIndicatorsUseCase(
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

    /** Five days of hourly bars, so daily pivots have >= 2 distinct UTC days. */
    private val candles: List<Candle> = (0 until 120).map { i ->
        val base = 100.0 + i * 0.2 + sin(i / 5.0) * 3
        Candle(
            timestamp = i * 3_600_000L,
            open = base,
            high = base + 1.5,
            low = base - 1.5,
            close = base + 0.4,
            volume = 1000.0 + (i % 5) * 300.0,
        )
    }

    private val allOn = IndicatorToggles(
        stochastic = true,
        obv = true,
        moneyFlowIndex = true,
        keltner = true,
        donchian = true,
        pivotPoints = true,
    )

    @Test
    fun `computes every newly wired overlay when its toggle is on`() {
        val r = useCase(candles, allOn)

        assertNotNull(r.stochasticK)
        assertNotNull(r.stochasticD)
        assertNotNull(r.obv)
        assertNotNull(r.moneyFlowIndex)
        assertNotNull(r.keltnerUpper)
        assertNotNull(r.keltnerMiddle)
        assertNotNull(r.keltnerLower)
        assertNotNull(r.donchianUpper)
        assertNotNull(r.donchianMiddle)
        assertNotNull(r.donchianLower)
        assertNotNull(r.pivotLevels)
    }

    @Test
    fun `computes nothing for the new overlays when their toggles are off`() {
        val r = useCase(candles, IndicatorToggles())

        assertNull(r.stochasticK)
        assertNull(r.stochasticD)
        assertNull(r.obv)
        assertNull(r.moneyFlowIndex)
        assertNull(r.keltnerUpper)
        assertNull(r.donchianUpper)
        assertNull(r.pivotLevels)
    }

    @Test
    fun `bounded oscillators stay within their 0-100 range`() {
        val r = useCase(candles, allOn)

        assertTrue(r.stochasticK!!.all { it in 0.0..100.0 })
        assertTrue(r.stochasticD!!.all { it in 0.0..100.0 })
        assertTrue(r.moneyFlowIndex!!.all { it in 0.0..100.0 })
    }

    @Test
    fun `channel bands bracket their midline`() {
        val r = useCase(candles, allOn)

        for (i in candles.indices) {
            assertTrue("keltner upper below mid at $i", r.keltnerUpper!![i] >= r.keltnerMiddle!![i])
            assertTrue("keltner lower above mid at $i", r.keltnerLower!![i] <= r.keltnerMiddle!![i])
            assertTrue("donchian inverted at $i", r.donchianUpper!![i] >= r.donchianLower!![i])
        }
    }

    @Test
    fun `pivot resistances sit above the pivot and supports below it`() {
        val p = useCase(candles, allOn).pivotLevels!!

        assertTrue(p.r1 > p.pivot)
        assertTrue(p.r2 > p.r1)
        assertTrue(p.s1 < p.pivot)
        assertTrue(p.s2 < p.s1)
    }

    @Test
    fun `all computed series align with the candle count`() {
        val r = useCase(candles, allOn)

        assertTrue(r.stochasticK!!.size == candles.size)
        assertTrue(r.obv!!.size == candles.size)
        assertTrue(r.moneyFlowIndex!!.size == candles.size)
        assertTrue(r.keltnerUpper!!.size == candles.size)
        assertTrue(r.donchianUpper!!.size == candles.size)
    }

    @Test
    fun `degenerate input does not throw`() {
        // Empty and single-bar series must be handled without exceptions.
        useCase(emptyList(), allOn)
        useCase(candles.take(1), allOn)
    }
}
