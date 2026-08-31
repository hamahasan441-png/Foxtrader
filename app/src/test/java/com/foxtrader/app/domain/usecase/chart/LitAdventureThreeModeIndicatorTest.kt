package com.foxtrader.app.domain.usecase.chart

import com.foxtrader.app.domain.model.Bias
import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.LitXAnalysis
import com.foxtrader.app.domain.model.LitXConfidence
import com.foxtrader.app.domain.model.LitXConfig
import com.foxtrader.app.domain.model.LitXGrade
import com.foxtrader.app.domain.model.LitXMode
import com.foxtrader.app.domain.model.LitXSignal
import com.foxtrader.app.domain.model.LitXStage
import com.foxtrader.app.domain.model.SignalProfile
import com.foxtrader.app.domain.model.SignalSource
import com.foxtrader.app.domain.model.Timeframe
import com.foxtrader.app.domain.usecase.signalintel.SignalEvidenceReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production contract for the three trader-facing LiT Adventure indicator modes. */
class LitAdventureThreeModeIndicatorTest {

    @Test
    fun `profiles resolve to fast balanced and power rule packages`() {
        val fast = LitXConfig.preset(SignalProfile.SCALPING)
        assertEquals("Fast Scalp", fast.adventureModeLabel)
        assertEquals(LitXMode.SWEEP_REVERSAL, fast.mode)
        assertEquals(LitXGrade.B, fast.minGrade)
        assertEquals(68, fast.minConfidenceScore)
        assertFalse(fast.requireStrongMss)
        assertFalse(fast.requireHtfAlignment)
        assertFalse(fast.requireDirectionalZone)

        val balanced = LitXConfig.preset(SignalProfile.INTRADAY)
        assertEquals("Balanced Trade", balanced.adventureModeLabel)
        assertEquals(LitXMode.PRECISION, balanced.mode)
        // Balanced is the default package, so it has to be able to fire.
        // Measured on 5 000 bars each of EURUSD, GBPUSD, AUDUSD, USDJPY and
        // XAUUSD on M15 and H1, the previous values published nothing on any of
        // the ten; these publish on all ten. Both gates remain switchable.
        assertEquals(LitXGrade.B, balanced.minGrade)
        assertEquals(68, balanced.minConfidenceScore)
        assertFalse(balanced.requireStrongMss)
        assertFalse(balanced.requireHtfAlignment)
        assertFalse(balanced.requireDirectionalZone)

        val power = LitXConfig.preset(SignalProfile.SWING)
        assertEquals("Power Trade", power.adventureModeLabel)
        assertEquals(LitXMode.SNIPER, power.mode)
        assertEquals(LitXGrade.A_PLUS, power.minGrade)
        assertEquals(90, power.minConfidenceScore)
        assertTrue(power.requireStrongMss)
        assertTrue(power.requireHtfAlignment)
        // Power Trade stays the strictest package by grade, score and its
        // SNIPER gates. It does not carry the premium/discount rule, which no
        // market in the sample ever satisfied.
        assertFalse(power.requireDirectionalZone)
        assertTrue(power.displacementAtrMultiple >= balanced.displacementAtrMultiple)
    }

    @Test
    fun `chart signal exposes three mode identity and remains pinned to confirmation bar`() {
        val computer = SignalComputer(SignalEvidenceReducer())
        val candles = candles()

        val expected = listOf(
            "SWEEP_REVERSAL" to ("FAST_SCALP" to "Fast Scalp"),
            "PRECISION" to ("BALANCED_TRADE" to "Balanced"),
            "SNIPER" to ("POWER_TRADE" to "Power"),
        )

        expected.forEach { (rawMode, expectedMode) ->
            val analysis = analysis(rawMode, candles)
            val chartSignal = computer.computeSignals(
                litXAnalysis = analysis,
                tradeProAnalysis = null,
                smtDivergences = emptyList(),
                candles = candles,
                latestConfirmedIndex = candles.lastIndex,
            ).single { it.source == SignalSource.LITX }

            assertEquals(expectedMode.first, chartSignal.variant)
            assertTrue(chartSignal.label.orEmpty().contains(expectedMode.second))
            assertEquals(candles.lastIndex, chartSignal.barIndex)
            assertTrue(chartSignal.isLive)
        }
    }

    private fun analysis(rawMode: String, candles: List<Candle>): LitXAnalysis {
        val signal = LitXSignal(
            symbol = "EURUSD",
            timeframe = Timeframe.M1,
            direction = Direction.BULLISH,
            stage = LitXStage.VALIDATED,
            entry = 1.1020,
            stopLoss = 1.1000,
            takeProfit1 = 1.1060,
            takeProfit2 = 1.1080,
            riskReward = 2.0,
            confidence = LitXConfidence(92, LitXGrade.A_PLUS, emptyList()),
            zone = null,
            rationale = "confirmed test setup",
            timestamp = candles.last().timestamp,
            confirmationIndex = candles.lastIndex,
            confirmations = listOf("MODE_$rawMode", "LIQUIDITY_SWEEP", "MSS", "DISPLACEMENT"),
        )
        return LitXAnalysis(
            symbol = "EURUSD",
            timeframe = Timeframe.M1,
            stage = LitXStage.VALIDATED,
            bias = Bias.BULLISH,
            htfBias = Bias.BULLISH,
            displacement = null,
            mitigationBlocks = emptyList(),
            premiumDiscount = null,
            signal = signal,
            narrative = signal.rationale,
            timestamp = signal.timestamp,
        )
    }

    private fun candles(): List<Candle> = (0..2).map { i ->
        val open = 1.1000 + i * 0.0010
        Candle(
            timestamp = 1_700_000_000_000L + i * 60_000L,
            open = open,
            high = open + 0.0015,
            low = open - 0.0010,
            close = open + 0.0005,
            volume = 1_000.0,
        )
    }
}
