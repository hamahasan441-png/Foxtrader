package com.foxtrader.app.domain.usecase.risk

import com.foxtrader.app.domain.model.Candle
import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.PositionSizingMethod
import com.foxtrader.app.domain.model.RiskConfig
import com.foxtrader.app.domain.model.StopMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [RiskEngine].
 *
 * Covers:
 * - Position sizing methods
 * - Stop-loss calculation methods
 * - Pre-trade risk gating (daily loss, drawdown, consecutive losses)
 * - Auto-halt and resume
 * - Kelly criterion estimation
 * - Trade outcome tracking
 */
class RiskEngineTest {

    private lateinit var engine: RiskEngine

    @Before
    fun setup() {
        engine = RiskEngine()
        engine.updateConfig(
            RiskConfig(
                accountBalance = 10_000.0,
                riskPercentPerTrade = 1.0,
                maxDailyLossPercent = 3.0,
                maxDrawdownPercent = 10.0,
                maxConsecutiveLosses = 5,
                fixedRiskAmount = 100.0,
                fixedLots = 0.1,
            )
        )
        engine.updateBalance(10_000.0)
    }

    // -------------------------------------------------------------------------
    // POSITION SIZING
    // -------------------------------------------------------------------------

    @Test
    fun `percentage risk sizing produces proportional volume`() {
        engine.updateConfig(engine.getConfig().copy(sizingMethod = PositionSizingMethod.PERCENTAGE_RISK))
        val result = engine.calculatePositionSize("EURUSD", 1.1000, 1.0950)
        assertTrue("Volume should be > 0", result.volume > 0)
        // 1% of 10000 = 100, stop = 0.0050, volume ≈ 100 / (0.0050 * 100000) = 0.2
        assertEquals(0.2, result.volume, 0.05)
    }

    @Test
    fun `fixed lots sizing returns configured fixed lots`() {
        engine.updateConfig(engine.getConfig().copy(sizingMethod = PositionSizingMethod.FIXED_LOTS, fixedLots = 0.1))
        val result = engine.calculatePositionSize("EURUSD", 1.1000, 1.0950)
        assertEquals(0.1, result.volume, 0.001)
    }

    @Test
    fun `fixed risk sizing uses configured fixed risk amount`() {
        engine.updateConfig(engine.getConfig().copy(sizingMethod = PositionSizingMethod.FIXED_RISK, fixedRiskAmount = 200.0))
        val result = engine.calculatePositionSize("EURUSD", 1.1000, 1.0900)
        assertTrue("Volume > 0", result.volume > 0)
        // 200 / (0.01 * 100000) = 0.2
        assertEquals(0.2, result.volume, 0.05)
    }

    @Test
    fun `ATR sizing falls back to percentage risk with insufficient data`() {
        engine.updateConfig(engine.getConfig().copy(sizingMethod = PositionSizingMethod.ATR_BASED))
        val result = engine.calculatePositionSize("EURUSD", 1.1000, 1.0950, candles = null)
        assertTrue(result.warnings.any { it.contains("Insufficient", ignoreCase = true) })
        assertTrue(result.volume > 0) // Falls back to percentage risk
    }

    @Test
    fun `zero stop distance is handled gracefully`() {
        val result = engine.calculatePositionSize("EURUSD", 1.1000, 1.1000) // same entry and stop
        assertTrue("Should warn about zero stop", result.warnings.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // STOP LOSS
    // -------------------------------------------------------------------------

    @Test
    fun `fixed stop-loss is 0_5 percent of entry`() {
        val sl = engine.calculateStopLoss(1.1000, Direction.BULLISH, StopMethod.FIXED)
        val expected = 1.1000 - (1.1000 * 0.005)
        assertEquals(expected, sl, 1e-6)
    }

    @Test
    fun `fixed stop for bearish trade is above entry`() {
        val sl = engine.calculateStopLoss(1.1000, Direction.BEARISH, StopMethod.FIXED)
        assertTrue("Bearish SL should be above entry", sl > 1.1000)
    }

    @Test
    fun `structure stop uses provided level`() {
        val structureLevel = 1.0900
        val sl = engine.calculateStopLoss(1.1000, Direction.BULLISH, StopMethod.STRUCTURE, structureLevel = structureLevel)
        assertEquals(structureLevel, sl, 1e-10)
    }

    @Test
    fun `structure stop falls back to fixed when level is null`() {
        val sl = engine.calculateStopLoss(1.1000, Direction.BULLISH, StopMethod.STRUCTURE, structureLevel = null)
        // Should equal the fixed stop fallback
        val expected = 1.1000 - (1.1000 * 0.005)
        assertEquals(expected, sl, 1e-6)
    }

    // -------------------------------------------------------------------------
    // PRE-TRADE RISK GATE
    // -------------------------------------------------------------------------

    @Test
    fun `fresh engine allows trade`() {
        val check = engine.canOpenTrade(100.0)
        assertTrue(check.allowed)
        assertTrue(check.reasons.isEmpty())
    }

    @Test
    fun `trading is blocked when manually halted`() {
        engine.haltTrading("manual halt")
        val check = engine.canOpenTrade(100.0)
        assertFalse(check.allowed)
        assertTrue(check.reasons.any { it.contains("halted", ignoreCase = true) })
    }

    @Test
    fun `resume clears halt`() {
        engine.haltTrading("test")
        assertTrue(engine.isTradingHalted())
        engine.resumeTrading()
        assertFalse(engine.isTradingHalted())
        assertTrue(engine.canOpenTrade(100.0).allowed)
    }

    // -------------------------------------------------------------------------
    // AUTO-HALT / DRAWDOWN
    // -------------------------------------------------------------------------

    @Test
    fun `auto-halt triggers on excessive drawdown`() {
        val config = engine.getConfig()
        // Record a 15% loss to exceed 10% drawdown limit
        val lossAmount = config.accountBalance * 0.15
        engine.recordTrade(-lossAmount, "EURUSD")
        assertTrue("Engine should be halted", engine.isTradingHalted())
        assertTrue("Halt reason should mention drawdown", engine.getRiskStatus().haltReason.contains("drawdown", ignoreCase = true))
    }

    @Test
    fun `consecutive loss auto-halt triggers at limit`() {
        val config = engine.getConfig()
        // Record exactly maxConsecutiveLosses losses
        repeat(config.maxConsecutiveLosses) {
            engine.recordTrade(-50.0, "EURUSD") // small loss, won't exceed drawdown alone
        }
        assertTrue("Should be halted after ${config.maxConsecutiveLosses} consecutive losses", engine.isTradingHalted())
    }

    @Test
    fun `winning trade resets consecutive loss counter`() {
        engine.recordTrade(-50.0, "EURUSD")
        engine.recordTrade(-50.0, "EURUSD")
        engine.recordTrade(200.0, "EURUSD") // win resets
        assertEquals(0, engine.getConsecutiveLosses())
    }

    // -------------------------------------------------------------------------
    // KELLY CRITERION
    // -------------------------------------------------------------------------

    @Test
    fun `kelly returns default risk pct without enough trades`() {
        val kelly = engine.calculateKellyPercent()
        // With < 5 wins and < 3 losses, returns the configured default
        val expected = engine.getConfig().riskPercentPerTrade / 100.0
        assertEquals(expected, kelly, 1e-6)
    }

    @Test
    fun `kelly is positive with winning track record`() {
        // 7 wins of 200, 3 losses of 100 — positive edge
        repeat(7) { engine.recordTrade(200.0, "EURUSD") }
        repeat(3) { engine.recordTrade(-100.0, "EURUSD") }
        val kelly = engine.calculateKellyPercent()
        assertTrue("Kelly should be positive with good track record", kelly > 0)
        assertTrue("Kelly should be capped at 25%", kelly <= 0.25)
    }

    // -------------------------------------------------------------------------
    // BALANCE / STATUS
    // -------------------------------------------------------------------------

    @Test
    fun `balance updates correctly after profit and loss`() {
        engine.recordTrade(500.0, "EURUSD")
        assertEquals(10_500.0, engine.getBalance(), 1e-6)
        engine.recordTrade(-200.0, "GBPUSD")
        assertEquals(10_300.0, engine.getBalance(), 1e-6)
    }

    @Test
    fun `risk status reflects current engine state`() {
        engine.recordTrade(-300.0, "EURUSD")
        val status = engine.getRiskStatus()
        assertEquals(9_700.0, status.balance, 1e-6)
        assertTrue(status.drawdownPercent > 0)
        assertFalse(status.halted)
    }

    @Test
    fun `reset restores engine to initial state`() {
        engine.recordTrade(-500.0, "EURUSD")
        engine.haltTrading("test")
        engine.reset()

        assertFalse(engine.isTradingHalted())
        assertEquals(engine.getConfig().accountBalance, engine.getBalance(), 1e-6)
        assertTrue(engine.canOpenTrade(100.0).allowed)
    }

    // -------------------------------------------------------------------------
    // DAILY LOSS
    // -------------------------------------------------------------------------

    @Test
    fun `daily loss is 0 with no trades`() {
        assertEquals(0.0, engine.getDailyLoss(), 1e-6)
    }

    @Test
    fun `daily loss accumulates within the day`() {
        engine.recordTrade(-100.0, "EURUSD")
        engine.recordTrade(-50.0, "GBPUSD")
        assertEquals(150.0, engine.getDailyLoss(), 1e-6)
    }

    @Test
    fun `daily loss excludes profits`() {
        engine.recordTrade(-200.0, "EURUSD")
        engine.recordTrade(500.0, "GBPUSD")
        // Net is positive — daily loss should be 0 from net perspective
        // But implementation sums the negatives only
        assertEquals(200.0, engine.getDailyLoss(), 1e-6)
    }

    private companion object {
        @Suppress("unused")
        fun candles(count: Int): List<Candle> = (0 until count).map { i ->
            val p = 1.1000 + i * 0.001
            Candle(System.currentTimeMillis() + i * 60_000L, p, p + 0.001, p - 0.001, p, 100.0)
        }
    }
}
