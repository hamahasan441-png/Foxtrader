package com.foxtrader.app.domain.usecase.tradepro

import com.foxtrader.app.domain.model.Direction
import com.foxtrader.app.domain.model.tradepro.HoldZone
import com.foxtrader.app.domain.model.tradepro.HoldZoneType
import com.foxtrader.app.domain.model.tradepro.TradeProConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeProRiskGuardTest {

    private val guard = TradeProRiskGuard()
    private val config = TradeProConfig()

    private fun buyZone(low: Double, high: Double) = HoldZone(
        type = HoldZoneType.BUY_HOLD, high = high, low = low,
        startIndex = 0, endIndex = 1, startTimestamp = 0L, endTimestamp = 60_000L,
        stackedCount = 2, strength = 60.0, defended = true,
    )

    @Test
    fun `3-contract plan splits into thirds and matches the framework break-even`() {
        val plan = guard.buildManagementPlan(config)
        assertEquals(3, plan.contracts)
        assertEquals(1, plan.t1Contracts)
        assertEquals(1, plan.t2Contracts)
        assertEquals(1, plan.runnerContracts)
        assertEquals(9.0, plan.totalRiskPoints, 1e-6)
        // T1+T2 = +12 pts, total risk 9 pts => 9 / (12+9) ~= 42.86%
        assertEquals(0.4286, plan.breakevenWinRate, 1e-3)
    }

    @Test
    fun `clean day is allowed to trade`() {
        val d = guard.canTrade(TradeProDailyState(), config)
        assertTrue(d.allowed)
    }

    @Test
    fun `three consecutive losses halts the day`() {
        val d = guard.canTrade(TradeProDailyState(consecutiveLosses = 3), config)
        assertFalse(d.allowed)
    }

    @Test
    fun `hitting the daily point cap halts the day`() {
        val d = guard.canTrade(TradeProDailyState(cumulativeLossPoints = 27.0), config)
        assertFalse(d.allowed)
    }

    @Test
    fun `low compliance halts the day`() {
        val d = guard.canTrade(TradeProDailyState(compliancePercent = 60.0), config)
        assertFalse(d.allowed)
    }

    @Test
    fun `structural stop for a long sits below the zone with minimum room`() {
        // Far zone: stop should sit just beyond the zone low, well past the 3pt minimum.
        val stopFar = guard.structuralStop(entry = 100.0, direction = Direction.BULLISH, zone = buyZone(90.0, 95.0), config = config)
        assertEquals(89.75, stopFar, 1e-6)
        // Tight zone: minimum 3pt distance dominates.
        val stopTight = guard.structuralStop(entry = 100.0, direction = Direction.BULLISH, zone = buyZone(98.0, 99.0), config = config)
        assertEquals(97.0, stopTight, 1e-6)
    }

    @Test
    fun `targets for a long are laddered above entry and respect the magnet`() {
        val t = guard.targets(entry = 100.0, direction = Direction.BULLISH, config = config, runnerMagnet = 112.0)
        assertEquals(104.0, t.t1, 1e-6)
        assertEquals(108.0, t.t2, 1e-6)
        assertEquals(112.0, t.runner, 1e-6)
    }

    @Test
    fun `runner falls back to configured points when no magnet`() {
        val t = guard.targets(entry = 100.0, direction = Direction.BULLISH, config = config, runnerMagnet = null)
        assertEquals(116.0, t.runner, 1e-6)
    }

    @Test
    fun `short targets ladder below entry`() {
        val t = guard.targets(entry = 100.0, direction = Direction.BEARISH, config = config, runnerMagnet = null)
        assertEquals(96.0, t.t1, 1e-6)
        assertEquals(92.0, t.t2, 1e-6)
        assertEquals(84.0, t.runner, 1e-6)
    }
}
